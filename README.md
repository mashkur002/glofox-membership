# Glofox Memberships Purchase API

A small Spring Boot service that sells memberships for a single studio. It lists
the seeded plans, purchases a membership through a payment port, fetches a
purchase, and soft-cancels one. Storage is in-memory, and every business rule is
safe under concurrent requests.

This was built as the Glofox backend take-home, and the original brief is
reproduced in the [appendix](#appendix-original-exercise-brief). The design
rationale is in [`SUBMISSION.md`](SUBMISSION.md),
[`docs/01-prd/PRD.md`](docs/01-prd/PRD.md) and
[`docs/02-trd/TRD.md`](docs/02-trd/TRD.md).

## Contents

1. [Overview](#1-overview)
2. [Running it](#2-running-it)
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
| Framework | Spring Boot 4.0.0 (Spring Framework 7, Spring Security), Jackson 3 |
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
./gradlew bootRun        # serves on :5000 and logs the 4 seeded plans
```

In PowerShell, set the same four variables first:

```powershell
$env:MEMBERSHIPS_ADMIN_USERNAME='admin'; $env:MEMBERSHIPS_ADMIN_PASSWORD='change-me-admin'
$env:MEMBERSHIPS_USER_USERNAME='user';   $env:MEMBERSHIPS_USER_PASSWORD='change-me-user'
./gradlew bootRun
```

The two usernames must differ.

### API documentation

Once the app is running, the API is documented at:

| Page | URL |
|---|---|
| Swagger UI | <http://localhost:5000/swagger-ui.html> |
| ReDoc | <http://localhost:5000/redoc.html> |
| OpenAPI spec (JSON) | <http://localhost:5000/v3/api-docs> |

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
curl -u user:change-me-user -X POST localhost:5000/v1/memberships/purchases \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 2f1c6a0e-order-42' \
  -d '{"member_name":"Alice","plan_id":"33333333-3333-3333-3333-333333333333","start_date":"2027-01-15"}'
```

`start_date` has to be today or later in the server's timezone. The brief's
example date, `2026-09-01`, is now in the past and gets a `400`.

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

On an `Idempotency-Key` replay you get the original `201` and body back, with
`Idempotency-Replayed: true`, and nothing is charged a second time.

### Error contract

| Status | `code` | When |
|---|---|---|
| 400 | `validation_failed` | A field is missing or too long, the date or JSON is malformed, the path id isn't a UUID, or the name is blank. Field errors are listed in `errors[]` |
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
| 502 | `payment_gateway_error` | The gateway couldn't be reached, so the charge is unconfirmed |
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
| Stretch: payment decline (`402`) told apart from gateway outage (`502`) | Implemented. Nothing in this repo throws the outage type yet (see §10) |
| Stretch: `GET /v1/memberships/purchases` with pagination | **Not implemented** |
| HTTP Basic authentication | Implemented. This was added after the PRD (see §11) |

## 5. Non-functional requirements

- **Correctness under concurrency.** Each contended rule rests on one atomic
  primitive, with no global lock:
  - promo inventory uses `AtomicInteger.getAndUpdate`
  - the one-unlimited-per-member rule uses `ConcurrentHashMap.putIfAbsent`
  - cancel uses a value-safe `replace` as a compare-and-set

  Because nothing is serialised, 500 different members can buy in parallel.
- **Atomicity without transactions.** A purchase runs reserve → charge → commit.
  Each claim pushes its undo onto a LIFO stack, and any failure unwinds the stack.
- **Swappable storage.** There are five ports in `store/`, and the in-memory
  adapters in `storage/memory/` carry no Spring annotations.
- **Testability.** The service and domain layers import no Spring and are tested
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

PRD §3 explains each of these:

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
- **The idempotency key claim is compensation entry #0.** A declined charge
  therefore releases the key, and an honest retry succeeds.
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
            PurchaseRequestValidator   shape only: presence, name length ≤ 100, strict YYYY-MM-DD
          └─► service/PurchaseService
                1. name not blank          (MemberKey.isBlank)
                2. start date not past     (StartDatePolicy)
                3. plan exists, plan active
                4. MemberKey from name
                5. [Idempotency-Key] claim ──► replay / in-progress / conflict / acquired
                6. [unlimited plan]  claim member slot        ─┐ each pushes
                7. [finite plan]     reserve promo slot       ─┘ its undo
                8. PaymentGateway.charge   (no lock held)
                9. PurchaseFactory.create → PurchaseStore.save
               10. [Idempotency-Key] complete
                on any RuntimeException: unwind undo stack (LIFO), rethrow
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
| `payment/` | `PaymentGateway` port and the default always-succeeding bean (vendor) | — |
| `web/` | Controllers, shape validation, DTOs, assemblers, exception-to-status mapping | `service`, `domain`, `store`, Spring |
| `config/` | Bean wiring, seeding, the single `Clock`, security | everything, Spring |

Only `config/` and `web/` import Spring. Today the code follows that rule by
convention and the test suite guards it incidentally (TRD §1.2). No dedicated
test enforces it.

## 9. Testing

The tests are organised by risk rather than by layer:

1. **Promo oversell.** 500 concurrent purchases produce exactly 5 sales, a
   counter of 0, and exactly 5 charges.
2. **One member, 500 concurrent unlimited purchases.** Exactly one succeeds. The
   test is `@RepeatedTest(10)` to raise how often it catches a race.
3. **500 different members buying unlimited.** All of them succeed, which proves
   the rule isn't enforced by serialising everyone.
4. **Payment failure.** Parameterised over decline, outage and the umbrella
   exception. Nothing persists, inventory is restored, and the claim is released.
5. **500 concurrent cancels of one purchase.** The slot comes back exactly once.
6. **500 concurrent requests with one idempotency key.** One charge in total.
7. **The full error contract**, security (401/403/roles), and bean wiring.

The concurrency tests were mutation-checked, with detection rates measured over
repeated runs (see `SUBMISSION.md`). Service, domain and storage tests run
without a Spring context.

## 10. Known gaps

`SUBMISSION.md` gives the reasons for each:

- **No gateway timeout.** `PaymentGatewayUnavailableException` has no producer yet.
- **Idempotency keys are never evicted.** The request body size isn't bounded either.
- **Framework errors use a different shape.** 404, 405 and 415 come back in
  Boot's default error format rather than problem+json with a `code`.
- **Authorisation is coarse.** The two roles have identical access, any
  authenticated caller can read or cancel any purchase, and idempotency keys
  aren't scoped to the caller.
- **Name normalisation only covers the ends of the string.** Look-alike letters
  and zero-width characters aren't folded.
- **The "nothing persisted on payment failure" unit assertion is vacuous.**
- **There's no CI pipeline, static analysis or coverage report** yet.

---

## Appendix: original exercise brief

The brief below is reproduced verbatim from the vendor skeleton.

# Glofox — Memberships Purchase Take-Home

Welcome, and thanks for interviewing with us.

This is a **backend take-home exercise in Java**. Aim for about **2–3 hours** of focused work. A solid core beats an unfinished kitchen sink — stop around **3–4 hours** even if stretch goals are unfinished.

You will discuss your submission in a follow-up technical conversation: design choices, trade-offs, and how you would evolve the system.

## The domain

Glofox is a SaaS platform for boutiques, studios, and gyms. Studio owners sell **memberships** (unlimited monthly plans and class packs) to members.

Your task is to build a small **Memberships Purchase API** for one studio, using **in-memory** storage.

## Timebox and what “done” means

**Required (core):**

1. `GET /v1/plans` — list seeded plans  
2. `POST /v1/memberships/purchases` — purchase a membership  
3. `GET /v1/memberships/purchases/{id}` — fetch a purchase by id  
4. Automated tests for the behaviours you consider highest risk  
5. A short write-up (`SUBMISSION.md`) with assumptions, API choices, known gaps, and approximate time spent  

If those work and the business rules below hold, you are done enough to submit.

## Pre-seeded plans

Plans are defined in [`SeedData.java`](src/main/java/com/glofox/memberships/config/SeedData.java) and logged when the app starts. You do **not** need a create-plan endpoint — seed them into your store at startup.

| Id | Name | Type | Active | Price (cents) | Credits | Remaining slots |
|----|------|------|--------|---------------|---------|-----------------|
| `11111111-1111-1111-1111-111111111111` | Monthly Unlimited | `unlimited` | yes | `8000` | — | — |
| `22222222-2222-2222-2222-222222222222` | 10-Class Pack | `pack` | yes | `5000` | `10` | — |
| `33333333-3333-3333-3333-333333333333` | Launch Promo Pack | `pack` | yes | `2500` | `5` | **`5`** |
| `44444444-4444-4444-4444-444444444444` | Legacy Plan | `unlimited` | **no** | `6000` | — | — |

`remaining_slots` is only set for the Launch Promo Pack. Other plans have unlimited inventory for this exercise.

## Required API

Use JSON. Request/response field names may be `snake_case` or `camelCase` — pick one and be consistent. Amounts must be integers in **cents**.

### `GET /v1/plans`

Return the seeded plans (including inactive ones). Include enough fields for a client to choose a plan (id, name, type, active, price in cents, credits / remaining slots when applicable).

### `POST /v1/memberships/purchases`

```http
POST /v1/memberships/purchases
Content-Type: application/json

{
  "member_name": "Alice",
  "plan_id": "11111111-1111-1111-1111-111111111111",
  "start_date": "2026-09-01"
}
```

Expected behaviour:

1. Validate request shape and semantics (blank name, invalid date, unknown plan, inactive plan, …).
2. Charge via the provided [`PaymentGateway`](src/main/java/com/glofox/memberships/payment/PaymentGateway.java) port (default bean always succeeds; replace or stub it in tests to simulate failure).
3. Create a purchase record (id, plan snapshot or plan id, amount in cents, status, start date, …).
4. Enforce the business rules below.
5. Return **`201 Created`** with a clear purchase payload including the purchase id and amount charged.

### `GET /v1/memberships/purchases/{id}`

Return the purchase, or an appropriate not-found response.

## Business rules (required)

- Monetary amounts are always integers in **cents** — never floats or decimal strings.
- Dates are calendar dates (`YYYY-MM-DD`), not timestamps. Timezones are out of scope for this exercise.
- You cannot purchase an **inactive** plan.
- A member (identified by `member_name` for this exercise) may have **at most one active unlimited** membership at a time.
- The **Launch Promo Pack** has finite `remaining_slots`. A successful purchase must decrement inventory. Concurrent purchases must **never** sell more than available or drive inventory below zero.
- If payment **fails**, no purchase may remain and inventory must not change.

## Deliberately open decisions

These are not hidden requirements — make a coherent choice and be ready to explain it:

- Exact error JSON shape and HTTP status mapping  
- Package / layer layout beyond the hints in this repo  
- Exact purchase response fields  
- How you represent “active unlimited” membership  
- Whether `member_name` uniqueness rules apply beyond the unlimited constraint  

## Payment gateway

[`PaymentGateway`](src/main/java/com/glofox/memberships/payment/PaymentGateway.java) is a port. [`AlwaysSucceedingPaymentGateway`](src/main/java/com/glofox/memberships/payment/AlwaysSucceedingPaymentGateway.java) is the default bean.

In tests, provide an implementation that throws `PaymentGateway.PaymentFailedException` so you can assert that a failed charge leaves no purchase and does not consume promo inventory.

## Storage

Use **in-memory** storage. [`storage/memory`](src/main/java/com/glofox/memberships/storage/memory/) is an empty package waiting for your implementation — you may organise domain, service, and HTTP layers however you prefer.

Design so swapping in a database-backed store later would be plausible.

## What we evaluate

- **Correctness** under the rules above, including payment failure and promo inventory  
- **Where the rules live** — handler vs service vs storage, and why  
- **Concurrency** — especially the last promo slots  
- **Tests** — which ones you chose and why  
- **Trade-offs you can defend** — simple and clear beats clever and opaque  
- **Ownership** — you can walk through and modify your solution in the follow-up  

Stretch features are optional. Core completeness beats volume.

## A note on AI tools

You are welcome to use AI assistants (Cursor, Copilot, ChatGPT, or anything else). Use them the way you would on the job: as a collaborator whose output you understand and own.

In the follow-up we will ask you to walk through your code and the choices behind it. If you would rather not use AI, that is also fine.

## Running the skeleton

Requires **Java 21+** (the project uses a Java 21 toolchain).

```bash
./gradlew bootRun    # starts on :5000; logs seeded plan ids
./gradlew test       # runs tests once you write them
```

No solution tests ship in this repo — write the ones that matter.

## Suggested layout (optional)

```text
src/main/java/com/glofox/memberships/
├── MembershipsApplication.java      # boots app; logs seed plans
├── config/SeedData.java             # seeded plans
├── payment/                         # PaymentGateway port + default success impl
├── storage/memory/                  # empty — your in-memory store
├── ...                              # your HTTP / domain / service packages
```

You may add Spring Web controllers, records, exceptions, etc. Prefer clarity over frameworks for their own sake.

## Stretch (optional)

Only if the core is solid — do not block submission on these:

- `POST /v1/memberships/purchases/{id}/cancel` — soft cancel; restore promo inventory when applicable  
- `Idempotency-Key` header on purchase  
- `GET /v1/memberships/purchases` with `page` / `limit` / `has_more`  
- Structured error body with machine-readable codes  
- Distinguish payment decline vs gateway outage  

## Submitting

1. Complete the core scope and `SUBMISSION.md` (see template below).  
2. Share your solution as instructed by your recruiter (zip, private repo, or branch).  

---

### `SUBMISSION.md` template

Copy to `SUBMISSION.md` and fill in:

```markdown
## Time spent
Approximately: _

## Assumptions
-

## API / design choices
-

## Tests
What I covered and why:

## Known gaps / what I would do next
-

## AI tools used (optional)
-
```

Good luck — we look forward to talking through your solution.
