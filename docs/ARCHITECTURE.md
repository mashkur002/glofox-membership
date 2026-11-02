# Architecture and design decisions

This document collects the design rationale behind the service: how it is
layered, why the purchase flow is ordered as it is, what each guarantee rests
on, and where the in-memory implementation stops short of production. The
[README](../README.md) covers running the service and using the API, and
[SUBMISSION.md](../SUBMISSION.md) covers assumptions and test evidence.

## 1. Layering

| Package | Role | May depend on |
|---|---|---|
| `domain/` | Immutable records and enums (`Plan`, `PlanSnapshot`, `Purchase`, `MemberKey`) | nothing |
| `store/` | Ports: storage interfaces exposing atomic primitives | `domain` |
| `storage/memory/` | In-memory adapters (`ConcurrentHashMap`, `AtomicInteger`) | `store`, `domain` |
| `service/` | Every business rule, the compensation stack, the domain exceptions | `domain`, `store`, `payment` |
| `payment/` | `PaymentGateway` port and the vendor's always-succeeding bean | — |
| `web/` | Controllers, shape validation, DTOs, the exception-to-status table | `service`, `domain`, `store`, Spring |
| `config/` | Bean wiring, seeding, the single `Clock`, security | everything, Spring |

`domain`, `store`, `storage` and `service` import no Spring. That keeps the
business rules constructible by hand, and every service test builds the service
that way, with no application context. The only Spring annotation outside
`config/` and `web/` is `@Component` on the vendor-owned
`AlwaysSucceedingPaymentGateway`, which is deliberately left unchanged.

`GET /v1/plans` has no service: listing enforces no rule, so `PlanController`
reads the catalogue and inventory ports directly.

## 2. The purchase flow

```
validate shape (web)  →  claim idempotency key  →  date / plan checks
   →  claim unlimited slot  →  reserve promo slot  →  charge  →  save  →  complete key
```

**Reserve before charging.** In-memory storage has no transaction to roll back,
so the order of the steps is the consistency mechanism. A claim is cheap and
reversible, but a charge is neither. Charging first and then discovering the
pack is sold out would take money for something that cannot be delivered.

**Compensation stack.** Each successful claim pushes its own undo onto an
`ArrayDeque`. On any `RuntimeException` the stack unwinds newest-first. A
compensation that itself throws is attached to the original exception with
`addSuppressed` rather than replacing it, and the unwind continues.

**No lock across I/O.** The contended decisions are settled by atomic claims
before the charge, so the slowest call in the flow blocks nobody. Five hundred
different members can buy in parallel.

## 3. Concurrency primitives

Each contended rule rests on exactly one atomic operation:

| Rule | Primitive |
|---|---|
| The promo pack is never oversold | `AtomicInteger.getAndUpdate(n -> n > 0 ? n - 1 : n)` |
| At most one active unlimited membership per member | `ConcurrentHashMap.putIfAbsent` on the normalised `MemberKey` |
| Cancel restores slots exactly once | Value-safe `replace` as compare-and-set on `ACTIVE → CANCELLED`, **before** any restore |
| One charge per idempotency key | `putIfAbsent` of an in-flight entry |

Cancel performs its compare-and-set before restoring anything. With the opposite
order, two concurrent cancels could both read `ACTIVE`, both restore the promo
slot, and leave the counter above its seeded maximum, which oversells the pack
through the cancel endpoint.

These guarantees hold within one process. A second instance would need the
atomic operations moved into shared storage, for example a conditional
`UPDATE ... WHERE remaining > 0`, a unique index on the member key, or a
conditional write.

## 4. Idempotency semantics

An optional `Idempotency-Key` header makes `POST /v1/memberships/purchases` safe
to retry. The request fingerprint is a SHA-256 digest of
`member_name | plan_id | start_date`.

| Key state | Same fingerprint | Different fingerprint |
|---|---|---|
| Absent | Process normally; claim the key | — |
| In flight | `409 idempotent_request_in_progress` | `409 idempotency_key_reuse` |
| Completed | **Replay the original response** | `409 idempotency_key_reuse` |

**Original-response replay, not current-resource replay.** When a keyed
purchase completes, the store keeps the immutable `Purchase` snapshot that was
returned to the first caller. A replay returns that snapshot with `201` and
`Idempotency-Replayed: true`, and charges nothing. If the purchase has since
been cancelled, the replay still shows `status: "active"`. This is deliberate:
a retry must be indistinguishable from the call it repeats. Clients that want
the latest state use **resource retrieval**, `GET /v1/memberships/purchases/{id}`.

**Ordering.** The key is checked after shape validation but *before* the
time-sensitive and state-dependent checks (start date not in the past, plan
exists and is active). Otherwise a retry of a purchase that already succeeded
would be rejected as soon as its `start_date` passed. A new key still goes
through every check.

**No stuck keys.** The key claim is compensation entry #0. If anything fails
before completion, whether a past date, an inactive plan, a sold-out pack or a
declined charge, the unwind releases the key so an honest retry can succeed.

**Limitation.** Keys are never evicted. A real store needs a TTL, and keys
should be scoped to the authenticated caller.

## 5. Payment succeeds, persistence fails

**The scenario.** The flow reserves inventory, the charge succeeds, and then
saving the purchase (or completing the idempotency key) fails.

**What this implementation does.** The unwind releases the promo slot, the
unlimited claim and the idempotency key. It **cannot** reverse the charge: the
payment port exposes no refund or void, so the charge is not on the
compensation stack. The service logs the failure at ERROR with the purchase id
and the gateway transaction id so it can be reconciled manually. The member has
been charged and holds no purchase.

**Why it is worse than it looks.** Storage is in-memory, so the payment
provider is the only durable record. If the process dies between the charge and
the save, even the log line's context is lost, and there is no local record of
intent to reconcile against. With in-memory maps, a process crash is the only
realistic trigger for this window.

**Production design.** These are not implemented here, and each needs
infrastructure the exercise excludes:

1. **Durable intent before money moves.** Write the purchase as `PENDING` in the
   same database transaction that takes the inventory slot and the unlimited
   claim, then charge, then transition it to `ACTIVE`. A crash then leaves a
   record to reconcile.
2. **Authorise, then capture.** Where the provider supports it, place an
   authorisation hold, commit the purchase, and only then capture. A failed
   commit voids the hold instead of refunding a settled charge.
3. **Idempotent payment calls.** Pass a provider idempotency key derived from
   the purchase id, so a retried charge or capture never moves money twice.
4. **Reconciliation.** A scheduled job compares `PENDING` purchases older than a
   threshold against the provider. It completes those that were charged and
   cancels those that were not.
5. **Saga compensation.** Model the purchase as a saga whose compensating step
   for a captured charge is an explicit refund. Execution is driven from the
   durable state, for example through an outbox, so a refund survives a
   restart.

## 6. Validation and error contract

Errors are RFC 9457 `application/problem+json` with a stable machine-readable
`code`. `type` is derived from `code`, so the two cannot disagree. Every
exception-to-status decision is one row in `ApiExceptionHandler`.

- **Shape problems** produce `400 validation_failed`, with every field error
  listed at once in `errors[]` as `{field, message}`. The shape checks are a
  missing field, a `member_name` over 100 characters, a whitespace-only
  `member_name`, and a `start_date` that is not strictly `YYYY-MM-DD`. A blank
  name is a field error here (an amendment to the original design, which left
  blankness to the service), and the service keeps the same check as a
  backstop. Both use `MemberKey.isBlank`, so there is one definition of blank.
- **Unreadable requests** also produce `400 validation_failed`, without
  `errors[]`: malformed JSON, a JSON `null` body, or a non-UUID path id.
- **Rule conflicts** are all `409`, distinguished by `code`: `plan_inactive`,
  `unlimited_membership_exists`, `plan_sold_out`,
  `purchase_already_cancelled`, `idempotency_key_reuse` and
  `idempotent_request_in_progress`.
- **Payment.** A decline is `402 payment_declined`; an outage is
  `502 payment_gateway_error`. They mean opposite things to a client: try
  another card, or retry the same one. The `detail` is fixed text, and the
  gateway's own message stays in the server log.
- **A past `start_date`** is `400 start_date_in_past`.
- **Unknown ids** are `404`; a malformed id is `400`.

## 7. Key decisions

| Decision | Choice | Why |
|---|---|---|
| Wire format | `snake_case`, money in integer cents, dates as `YYYY-MM-DD` | One global Jackson naming strategy, so it cannot be applied inconsistently |
| Member identity | `member_name`, matched on an NFC-normalised, edge-trimmed (Unicode spaces included), `Locale.ROOT` case-folded key; stored as typed | Without it, `"alice"` bypasses the one-unlimited rule that `"Alice"` is subject to |
| "Active unlimited" | Status `ACTIVE` only; no expiry | Keeps the clock out of business rules and tests deterministic |
| Plan on purchase | Snapshot | A later price change must not rewrite what a member was charged |
| `start_date` | Reject strictly before today; no upper bound | Backdating is a billing decision |
| Cancel | Soft cancel; frees the unlimited slot and promo slot immediately; no refund | `CANCELLED` is not active |
| Double cancel | `409 purchase_already_cancelled` | A client cancelling twice has a bug |
| Time | One injected `Clock`, held by `StartDatePolicy` and `PurchaseFactory` only | No business rule can read "now" directly |

## 8. Security posture

| Area | State |
|---|---|
| Authentication | HTTP Basic on `/v1/**`, stateless, two BCrypt-hashed users whose credentials come only from the environment; startup fails without them. Every other path is denied, except the API docs and `/error` |
| Authorisation | Coarse. Both roles have identical access; any authenticated caller can read or cancel any purchase |
| Member identity | `member_name` is not bound to the authenticated principal, so any caller can claim the unlimited slot for any name (name squatting). The fix is a member id bound to the principal |
| Input bounds | `member_name` is capped at 100 characters; the request body size is not bounded |
| Log injection | Caller-supplied values are sanitised before logging, and most log lines use the server-generated purchase id |
| Information leaks | Unexpected errors return fixed text. A membership conflict never echoes the member name, which would otherwise make the endpoint an existence oracle |
| Gateway timeout | None. A hanging gateway would exhaust the servlet thread pool. `PaymentGatewayUnavailableException` exists but has no producer |
| Transport | TLS is expected to terminate at the load balancer or proxy |

## 9. Performance

The throughput ceiling is the servlet thread pool multiplied by gateway latency,
not lock contention, because no lock is held across the charge. The promo
counter's CAS does not spin once sold out, since `getAndUpdate` at zero computes
zero and succeeds immediately. All lookups are O(1). The memory cost grows with
distinct members and keys, and nothing is evicted.

## 10. Out of scope

Durable persistence and multi-instance deployment, plan CRUD, a real payment
provider, refunds and proration, spending pack credits, a member registry,
timezones, rate limiting, metrics and tracing, and paginated purchase listing.
