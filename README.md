# Glofox Memberships Purchase API

A small Spring Boot service that sells memberships for a single studio. It lists
the seeded plans, purchases a membership through a payment port, fetches a
purchase, and soft-cancels one. Storage is in-memory, and every business rule is
safe under concurrent requests.

Design rationale, trade-offs and production considerations are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Submission notes (assumptions,
test evidence, known gaps) are in [`SUBMISSION.md`](SUBMISSION.md).

## Contents

1. [Overview](#1-overview)
2. [Running it](#2-running-it) (local and AWS Elastic Beanstalk)
3. [API](#3-api)
4. [Functional scope](#4-functional-scope)
5. [Non-functional requirements](#5-non-functional-requirements)
6. [Deliberately out of scope](#6-deliberately-out-of-scope)
7. [Key business and service decisions](#7-key-business-and-service-decisions)
8. [Application flow and layer responsibilities](#8-application-flow-and-layer-responsibilities)
9. [Testing](#9-testing)
10. [Known gaps](#10-known-gaps)

## 1. Overview

| | |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 4.0.0 (Spring Framework 7): `spring-boot-starter-webmvc`, `spring-boot-starter-security`, `springdoc-openapi-starter-webmvc-ui` 3.0.3; Jackson 3 |
| Build | Gradle 9.5.1 wrapper (Kotlin DSL) |
| Tests | JUnit Jupiter 6, AssertJ, Mockito, MockMvc |
| Storage | In-memory, behind ports so a database could be swapped in |
| Wire format | JSON, `snake_case`, money as integer cents, dates as `YYYY-MM-DD` |
| Errors | RFC 9457 `application/problem+json` with a stable `code` field |
| Auth | HTTP Basic, stateless, with two users (`ADMIN`, `USER`) taken from the environment |

What it does:

- Lists every seeded plan, inactive ones included, with live promo inventory.
- Purchases a membership, charging through the payment port.
- Fetches a purchase by id.
- Soft-cancels a purchase and hands back its promo slot and the member's unlimited slot.
- Accepts an optional `Idempotency-Key` header so a purchase can be retried safely.

## 2. Running it

You need a Java 21+ JDK. The app **won't start** until all four credentials are
set. That's deliberate: there are no defaults, so a forgotten variable stops the
boot instead of leaving a guessable password in place.

```bash
export MEMBERSHIPS_ADMIN_USERNAME=admin MEMBERSHIPS_ADMIN_PASSWORD='change-me-admin'
export MEMBERSHIPS_USER_USERNAME=user   MEMBERSHIPS_USER_PASSWORD='change-me-user'
./gradlew bootRun        # serves on :8080 and logs the 4 seeded plans
```

In PowerShell, set the same four variables first:

```powershell
$env:MEMBERSHIPS_ADMIN_USERNAME='admin'; $env:MEMBERSHIPS_ADMIN_PASSWORD='change-me-admin'
$env:MEMBERSHIPS_USER_USERNAME='user';   $env:MEMBERSHIPS_USER_PASSWORD='change-me-user'
.\gradlew.bat bootRun
```

The two usernames must differ.

### Port

The port comes from the `PORT` environment variable and defaults to `8080`
(`server.port: ${PORT:8080}` in `application.yml`). The same jar runs everywhere.

| Environment | `PORT` | Listens on |
|---|---:|---:|
| Local development | not set | 8080 |
| Local override | `9090` | 9090 |
| AWS Elastic Beanstalk | `5000` | 5000 |

```bash
PORT=9090 ./gradlew bootRun                      # Linux / macOS
```

```powershell
$env:PORT = "9090"; .\gradlew.bat bootRun        # PowerShell
```

### AWS Elastic Beanstalk (Java SE platform)

Beanstalk's nginx proxy forwards port 80 to the application on port 5000.
[`.ebextensions/01-port.config`](.ebextensions/01-port.config) sets `PORT=5000`
as an environment property, and Spring Boot binds to all interfaces (`0.0.0.0`)
by default, so nothing else is needed.

1. `./gradlew bootJar` builds `build/libs/*.jar`.
2. Deploy a source bundle (zip) that contains that jar at its root together with
   the `.ebextensions/` directory. `.ebextensions` is read only from a source
   bundle; if you upload the bare jar, set `PORT=5000` in the environment's
   configuration instead.
3. Set the four `MEMBERSHIPS_*` credentials as environment properties in the
   Beanstalk console or with `eb setenv`. They are never committed, and the app
   refuses to start without them.

The proxy-to-port wiring follows Beanstalk's documented defaults for the Java SE
platform. It has not been exercised against a live environment from this
repository.

### API documentation

Once the app is running, the API is documented at:

| Page | URL |
|---|---|
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| ReDoc | <http://localhost:8080/redoc.html> |
| OpenAPI spec (JSON) | <http://localhost:8080/v3/api-docs> |

These pages and the spec are public and need no credentials. Every `/v1/**`
endpoint still requires HTTP Basic auth; use **Authorize** in Swagger UI to
try requests from the browser.

```bash
./gradlew test                            # full suite; test credentials come from src/test/resources
./gradlew test --tests '*MemberKeyTest'   # one test class
./gradlew test --rerun-tasks              # bypass Gradle's up-to-date cache
```

## 3. API

Every `/v1/**` endpoint needs HTTP Basic credentials, and every other path is
denied.

| Method | Path | Success |
|---|---|---|
| `GET` | `/v1/plans` | `200`, a bare array of all seeded plans in seed order |
| `POST` | `/v1/memberships/purchases` | `201`, purchase body plus `Location` and `Idempotency-Replayed` headers |
| `GET` | `/v1/memberships/purchases/{id}` | `200`, purchase body |
| `POST` | `/v1/memberships/purchases/{id}/cancel` | `200`, the purchase with `status: "cancelled"` |

```bash
curl -u user:change-me-user -X POST localhost:8080/v1/memberships/purchases \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 2f1c6a0e-order-42' \
  -d '{"member_name":"Alice","plan_id":"33333333-3333-3333-3333-333333333333","start_date":"2027-01-15"}'
```

`start_date` has to be today or later in the server's timezone. A past date
gets `400 start_date_in_past`.

A purchase response has these fields:

- `id`
- `member_name`, exactly as submitted
- `plan`, a snapshot taken at purchase time: `plan_id`, `name`, `type`, `price_cents`, `credits`
- `amount_cents`
- `status`, either `active` or `cancelled`
- `start_date`
- `transaction_id`
- `created_at`

A plan has `id`, `name`, `type` (`unlimited` or `pack`), `active`,
`price_cents`, `credits` and `remaining_slots`. `credits` and `remaining_slots`
are always present. A `null` means "doesn't apply" or "unlimited", which is a
different answer from `0`.

### Idempotency: replay versus retrieval

- **Idempotent replay.** Repeating a successful `POST` with the same
  `Idempotency-Key` and an identical body returns the *original* `201` and body,
  with `Idempotency-Replayed: true`. Nothing is charged a second time. The
  replayed body is the response the first request produced: if the purchase has
  since been cancelled, the replay still shows `status: "active"`, and if its
  `start_date` has since passed, the replay still succeeds.
- **Resource retrieval.** `GET /v1/memberships/purchases/{id}` returns the
  purchase's *current* state. Use it when you need the latest status.
- The same key with a different body is `409 idempotency_key_reuse`. If the
  first request with a key fails, the key is released and can be retried.

### Error contract

| Status | `code` | When |
|---|---|---|
| 400 | `validation_failed` | A field is missing, too long or blank (whitespace-only `member_name`), or the date is malformed; these are listed in `errors[]` as `{field, message}`. Also returned, without `errors[]`, when the body is unreadable (malformed JSON, or a JSON `null` body) or the path id isn't a UUID |
| 400 | `start_date_in_past` | `start_date` is before today (server timezone) |
| 401 | `unauthorized` | Credentials are missing or wrong. The response carries `WWW-Authenticate: Basic realm="memberships"` |
| 403 | `forbidden` | The caller is authenticated, but the path is outside `/v1/**` |
| 402 | `payment_declined` | The gateway declined the charge. Also the fallback for an unspecified payment failure |
| 404 | `plan_not_found` | The `plan_id` is well-formed but doesn't exist |
| 404 | `purchase_not_found` | The purchase id is well-formed but doesn't exist |
| 409 | `plan_inactive` | The plan exists but isn't on sale |
| 409 | `unlimited_membership_exists` | The member already holds an active unlimited membership. The name is never echoed back |
| 409 | `plan_sold_out` | The promo inventory is used up |
| 409 | `purchase_already_cancelled` | Cancel was called on a purchase that's already cancelled |
| 409 | `idempotency_key_reuse` | The same key came with a different payload |
| 409 | `idempotent_request_in_progress` | The same key arrived while the first request was still running |
| 502 | `payment_gateway_error` | The gateway couldn't be reached, didn't answer within `memberships.payment.timeout`, or already has `max-concurrent-charges` calls in flight. The charge is unconfirmed |
| 500 | `internal_error` | Something unexpected happened. Details go to the log and are never returned |

## 4. Functional scope

| Requirement | Status |
|---|---|
| Seed the four plans at startup. An unknown plan type fails the boot | Implemented |
| `GET /v1/plans`, inactive plans and live `remaining_slots` included | Implemented |
| Purchase: validate, charge, record a plan snapshot, return `201` | Implemented |
| Fetch a purchase by id | Implemented |
| Inactive plans can't be bought | Implemented |
| At most one active unlimited membership per member | Implemented, race-safe |
| The promo pack is never oversold and never goes below zero | Implemented, race-safe |
| A failed payment leaves no purchase behind and doesn't touch inventory | Implemented |
| Stretch: soft cancel that restores promo and unlimited slots | Implemented, race-safe |
| Stretch: `Idempotency-Key` on purchase | Implemented |
| Stretch: structured errors with machine-readable codes | Implemented |
| Stretch: payment decline (`402`) told apart from gateway outage (`502`) | Implemented. A gateway timeout or saturation produces the `502` |
| Gateway timeout and bulkhead | Implemented: `memberships.payment.timeout` (default `5s`) and `memberships.payment.max-concurrent-charges` (default `50`) |
| Stretch: `GET /v1/memberships/purchases` with pagination | **Not implemented** |
| HTTP Basic authentication | Implemented (`config/SecurityConfig`); added beyond the original scope |

## 5. Non-functional requirements

- **Correctness under concurrency.** Each contended rule rests on one atomic
  primitive, with no global lock:
  - promo inventory uses `AtomicInteger.getAndUpdate`
  - the one-unlimited-per-member rule uses `ConcurrentHashMap.putIfAbsent`
  - cancel uses a value-safe `replace` as a compare-and-set

  Because nothing is serialised, 500 different members can buy in parallel.
- **Atomicity without transactions.** A purchase runs reserve → charge → commit.
  Each claim pushes its undo onto a LIFO stack, and any failure unwinds the stack.
  A successful charge is **not** reversible here (see §10).
- **Swappable storage.** There are five ports in `store/`, and the in-memory
  adapters in `storage/memory/` carry no Spring annotations.
- **Testability.** The service, domain and storage layers import no Spring and are tested
  without an application context. Time comes from one injected `Clock`, held by
  only two collaborators.
- **Security.**
  - Credentials come only from the environment.
  - Routing denies by default.
  - Sessions are stateless.
  - Error bodies never leak internals or gateway messages.
  - Caller-supplied values are sanitised before they're logged.
- **Maintainability.** The business rules live in one class and the status
  mapping in one table. Nothing uses a framework for its own sake.

## 6. Deliberately out of scope

[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) explains each of these:

- durable persistence and multi-instance deployment
- creating or editing plans
- a real payment provider, refunds and proration
- spending pack credits
- a member registry, so `member_name` is the identity
- timezones
- rate limiting, metrics and tracing
- paginated purchase listing

## 7. Key business and service decisions

- **Member identity is `member_name`.** Names are matched on a normalised key:
  NFC normalisation, trimming at both ends (Unicode spaces such as `U+00A0`
  included), and lowercasing with `Locale.ROOT`. The name itself is stored
  exactly as typed. Interior spacing, zero-width characters and look-alike
  letters are not folded.
- **"Active unlimited" means status `ACTIVE`.** Memberships don't expire.
- **The plan is snapshotted onto the purchase**, so a later price change can't
  rewrite what a member was charged. `amount_cents` is stored separately from
  the snapshot price.
- **Reserve before charging.** A claim is cheap and can be undone, while a charge
  can't. Charging first would take money for something that might be sold out.
- **Cancel does its compare-and-set before restoring anything.** That way only
  one of several concurrent cancels gets to restore the slot. Cancelling issues
  no refund.
- **The idempotency key is claimed before the date and plan checks** and is
  compensation entry #0. A completed key replays the original response without
  re-validating; a new key still passes every check, and any failure (a past
  date, a declined charge) releases it so an honest retry succeeds.
- **Every rule conflict returns `409`**, and the `code` field says which rule. A
  malformed id is a `400`. An unknown id is a `404`.
- **Payment failure detail is fixed text.** The gateway's own message stays in
  the server log.
- **Authentication** is HTTP Basic with BCrypt-hashed in-memory users. Both roles
  currently have the same access; the split exists so it can be narrowed later.

## 8. Application flow and layer responsibilities

```
HTTP request
  └─► Security filter chain        HTTP Basic, stateless; 401/403 rendered as problem+json
      └─► web/PurchaseController
            PurchaseRequestValidator   shape: presence, name not blank, length ≤ 100, strict YYYY-MM-DD
          └─► service/PurchaseService
                1. name not blank          (backstop; MemberKey.isBlank)
                2. MemberKey from name
                3. [Idempotency-Key] claim ──► replay original / in-progress / conflict / acquired
                4. start date not past     (StartDatePolicy)
                5. plan exists, plan active
                6. [unlimited plan]  claim member slot        ─┐ each pushes
                7. [finite plan]     reserve promo slot       ─┘ its undo
                8. PaymentGateway.charge   (no lock held; NOT undoable)
                9. PurchaseFactory.create → PurchaseStore.save
               10. [Idempotency-Key] complete with the purchase snapshot
                on any RuntimeException: unwind undo stack (LIFO), rethrow;
                if the charge had succeeded, log it at ERROR for reconciliation
          └─► web/PurchaseAssembler → DTO → 201 Created + Location
  errors ─► web/ApiExceptionHandler   one table: exception class → status + code
```

`GET /v1/plans` has no service layer, because listing enforces no rule.
`PlanController` reads the catalogue and the inventory ports directly.

| Package | Responsibility | Depends on |
|---|---|---|
| `domain/` | Immutable records and enums: `Plan`, `PlanSnapshot`, `Purchase`, `MemberKey`, `PlanType`, `PurchaseStatus` | nothing |
| `store/` | **Ports.** Storage interfaces that expose atomic primitives (`tryReserve`, `tryClaim`, `compareAndSetStatus`, idempotency `claim`) | `domain` |
| `storage/memory/` | **Adapters.** `ConcurrentHashMap` / `AtomicInteger` implementations of the ports | `store`, `domain` |
| `service/` | Every business rule, the compensation stack, and the domain exceptions | `domain`, `store`, `payment` |
| `payment/` | `PaymentGateway` port, the default always-succeeding bean (vendor), and `TimeoutPaymentGateway` (timeout + bulkhead decorator) | Spring (`@Component` on the vendor bean only) |
| `web/` | Controllers, shape validation, DTOs, assemblers, exception-to-status mapping | `service`, `domain`, `store`, Spring |
| `config/` | Bean wiring, seeding, the single `Clock`, security | everything, Spring |

`domain/`, `store/`, `storage/` and `service/` import no Spring; beans are wired
in `config/`. The one exception outside `config/` and `web/` is the vendor-owned
`payment/AlwaysSucceedingPaymentGateway`, which is annotated `@Component` and
left unchanged. The boundary is held by convention; no dedicated test enforces it.

## 9. Testing

The tests are organised by risk rather than by layer:

1. **Promo oversell.** 500 concurrent purchases produce exactly 5 sales, a
   counter of 0, and exactly 5 charges.
2. **One member, 500 concurrent unlimited purchases.** Exactly one succeeds. The
   test is `@RepeatedTest(10)` to raise how often it catches a race.
3. **500 different members buying unlimited.** All of them succeed, which proves
   the rule isn't enforced by serialising everyone.
4. **Payment failure.** Parameterised over decline, outage and the umbrella
   exception. Inventory is restored, the unlimited claim and the idempotency
   key are released, and the original failure reaches the caller.
5. **500 concurrent cancels of one purchase.** The slot comes back exactly once.
6. **500 concurrent requests with one idempotency key.** One charge in total.
   Replays return the original response after a cancel and after the start
   date has passed.
7. **The full error contract**, security (401/403/roles), and bean wiring.

The concurrency tests were mutation-checked, with detection rates measured over
repeated runs (see `SUBMISSION.md`). Service, domain and storage tests run
without a Spring context.

## 10. Known gaps

`SUBMISSION.md` gives the reasons for each:

- **A timed-out charge may still land.** A timeout returns `502` and releases
  the slot, but the provider may complete the charge later. That late success is
  logged at ERROR with the transaction id for reconciliation; it is not refunded
  automatically.
- **Idempotency keys are never evicted.** The request body size isn't bounded either.
- **Framework errors use a different shape.** 404, 405 and 415 come back in
  Boot's default error format rather than problem+json with a `code`.
- **Authorisation is coarse.** The two roles have identical access, any
  authenticated caller can read or cancel any purchase, and idempotency keys
  aren't scoped to the caller.
- **Name normalisation only covers the ends of the string.** Look-alike letters
  and zero-width characters aren't folded.
- **A successful charge cannot be reversed.** If the charge succeeds and saving
  the purchase then fails, the slots and key are released but the member has
  been charged with no purchase recorded. It is logged at ERROR with the
  transaction id for manual reconciliation. The payment port has no refund, and
  storage is in-memory. The production design is in `docs/ARCHITECTURE.md`.
- **There's no CI pipeline, static analysis or coverage report** yet.
