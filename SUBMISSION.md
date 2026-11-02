# Submission notes

## Time spent

Approximately 3 hours on the required core, plus about an hour on the two
stretch features, preceded by a design pass (summarised in
`docs/ARCHITECTURE.md`).
The build is green at every commit, and core was complete and merged before any
stretch work began — so the core scope stands on its own.

## Assumptions

- **"Active unlimited" means status only.** A membership is active if its status
  is `ACTIVE`; no expiry window is evaluated, so unlimited memberships never
  lapse. Chosen because the alternative needs a term length the seed data does
  not carry, and it keeps the clock out of every business rule.
- **`member_name` is the member identity**, matched on an NFC-normalised,
  trimmed, case-folded key. There is no member registry, so `"Alice"` and `" ALICE "` are one person
  and two real people named Alice are indistinguishable.
- **Pack credits are recorded but never spent.** Consuming them is out of scope.
- **The plan is snapshotted onto the purchase**, so a later price change cannot
  rewrite what a member was charged.
- **`remaining_slots: null` means unlimited inventory, not zero.** Internally
  such plans have no counter at all.
- **Timezones are out of scope.** `start_date` is a calendar date; `created_at`
  is an instant from an injected clock.
- **Single process.** Concurrency safety is in-process only; a second instance
  would need the real store.
- **Authentication is HTTP Basic** with two in-memory users whose credentials
  come from the environment. It authenticates the *caller*, not the *member*:
  any authenticated caller can still purchase under any `member_name`, which is
  the root of the main security gap below.

## API / design choices

- **`snake_case` throughout**, applied as one global Jackson naming strategy so
  it cannot be applied inconsistently. The cost is that the wire contract is
  invisible at the DTOs, so a test asserts the wire names — including the
  negative case, since camelCase would otherwise serialise perfectly happily.
- **RFC 9457 `ProblemDetail` plus a `code` extension.** `code` is the stable
  machine-readable contract; `type` is derived from it, so the two cannot
  disagree.
- **All three rule violations are `409`** — the request is well-formed but
  conflicts with current state. They are distinguished by `code`, not by status.
- **`400` vs `404` on a purchase id:** `not-a-uuid` is `400` (a malformed
  question), a well-formed unknown UUID is `404` (a sensible question, negative
  answer).
- **`402` for a declined charge, `502` for a gateway outage.** The port's own
  javadoc already said "declined **or** the gateway errors"; two subtypes of the
  existing `PaymentFailedException` make that expressible without breaking any
  caller that catches the umbrella type. `PaymentGateway.java` gained 25 lines
  and lost none. The skeleton's `application.yml` (snake_case naming,
  springdoc paths, application name, `server.port: ${PORT:8080}`) and
  `build.gradle.kts` (the security and springdoc dependencies) were also
  changed; `SeedData`, `AlwaysSucceedingPaymentGateway` and
  `MembershipsApplication` were not.
- **Rules live in the service.** Controllers do shape validation and nothing
  else; stores own storage and the atomic primitives. Replace the web layer with
  a CLI and not one rule moves.
- **Ordering is reserve → charge → commit**, with a compensation stack. Claims
  are cheap and reversible, a charge is neither. Charging first and then finding
  the pack sold out would mean taking money for something undeliverable — a
  defect, not a trade-off.
- **No Spring in `domain/`, `store/`, `storage/` or `service/`.** Beans are
  wired in `config/`, so the service is constructible by hand, and every service
  test does exactly that. The one Spring annotation outside `config/` and
  `web/` is `@Component` on the vendor-owned
  `payment/AlwaysSucceedingPaymentGateway`, left byte-identical.
- **No `PlanService`.** Listing plans enforces no rule, and a pass-through layer
  added for symmetry is cost without benefit.
- **The clock is reachable only through two collaborators that expose no
  `now()`.** Two classes hold a `Clock`; zero business rules can read one.

## Stretch features included

All are implemented and tested on this branch.

- **`POST /v1/memberships/purchases/{id}/cancel`** — soft cancel, returning the
  promo slot and the member's unlimited slot. `200` with the updated purchase.
  **The compare-and-set comes before the restore, and that ordering is the whole
  point:** restore-then-CAS would let two concurrent cancels both read `ACTIVE`,
  both restore the slot, and leave the counter *above* its maximum — overselling
  a five-slot promo through the cancel endpoint. The CAS is what elects the one
  caller entitled to compensate. Reordering it fails 2 of 8 cancel tests
  immediately; 5 callers "successfully" cancel the same purchase.
- **Optional `Idempotency-Key` header** on purchase. A replay returns the
  original `201` and body plus `Idempotency-Replayed: true`; the same key with a
  different payload is `409 idempotency_key_reuse`, detected via a SHA-256
  fingerprint of the payload rather than by storing it again. A concurrent
  duplicate gets `409 idempotent_request_in_progress`.

  **A replay returns the original response, not the current resource.** The
  store keeps the immutable `Purchase` snapshot the first request returned, so a
  retry after a cancel still replays `status: "active"`. `GET /{id}` is how a
  client reads the current state. The key is claimed *before* the start-date
  and plan checks, so a retry still replays after its `start_date` has passed.
  A new key still goes through every check, and a failed check releases it.

  **An idempotency key is the same claim-or-fail pattern as the promo counter
  and the one-unlimited rule**, so the key claim became compensation entry #0 on
  the stack the purchase flow already had — reusing the existing unwind
  unchanged. No new concurrency mechanism was introduced, and it fixed the
  subtle case for free: a failed charge releases the key, so a client retrying
  after a declined card is not told "already in progress" forever.
- **HTTP Basic authentication** on `/v1/**` (stateless, BCrypt-hashed users from
  the environment, deny-by-default routing). 401 and 403 use the same
  problem+json shape as every other error.

## Tests

181 tests. Ordered by risk, not by layer.

1. **Promo inventory under 500 concurrent purchases** — exactly 5 succeed, 495
   get `409 plan_sold_out`, the counter settles at 0, and **5 cards are charged,
   not 500**. That last assertion is the one that catches an implementation
   which charges everyone and persists five.
2. **One member, 500 simultaneous unlimited purchases** — exactly one succeeds.
   The brief only mentions concurrency for promo slots, but this rule has the
   identical check-then-act race.
3. **500 *different* members buying unlimited** — all 500 succeed. This is what
   distinguishes "correct" from "serialised": one global lock would pass test 2
   while making the endpoint useless.
4. **Payment failure, parameterised over decline / outage / umbrella type** —
   inventory restored, the unlimited claim and the idempotency key released,
   and the original failure propagated with nothing suppressed.
   Parameterising is deliberate: the claim-release assertion is the one likely
   to be written for only one failure mode, and unlimited plans have no
   inventory counter to notice the leak.
5. **`remaining_slots == null` is unlimited** — guards the one-character bug of
   writing the check as `remaining > 0`, which would refuse every plan without
   an inventory limit.
6. **500 concurrent cancels of one purchase** — exactly one succeeds and the
   slot comes back exactly once, never twice.
7. **500 concurrent requests with one idempotency key** — one charge, and every
   other caller either replays the same purchase or is told it is in flight.
   Replays are checked to return the original response after a cancel and
   after the start date has passed.
8. Error contract, all thirteen rows, including that a membership conflict does
   not echo the member name.

**I checked the concurrency tests can actually fail, and measured how
reliably.** Three mutants, each run repeatedly:

| Broken on purpose | Symptom | Caught |
|---|---|---|
| inventory CAS → read-then-write | oversells; counter inflates to 6–20 | **3/16** by the oversell test alone; **5/5** by the full suite |
| `putIfAbsent` → `containsKey`+`put` | 2–3 unlimited memberships per member | **9/12** → **12/12** after `@RepeatedTest(10)` |
| cancel restores before the CAS | 2–6 callers cancel the same purchase | **5/5** |

The first row is the interesting one. **My headline oversell test is far weaker
than it looks** — only ~19% of runs catch the bug it was written for, because
only 5 of 500 attempts ever reach the race window. The failure-path test is what
has teeth: every charge fails and returns its slot, so the 5 slots recycle ~490
times per run, roughly a hundredfold more chances to lose an update.

That justified a decision that had looked like laziness: `release()` deliberately
does not clamp at the seeded maximum. A clamp would have pinned all those
inflated counters back to 5 and **inverted the result** — the mutant would have
survived every run instead of dying every run.

The measurement then changed the code. The one-unlimited race was detected in
only 9 of 12 runs, so that test now carries `@RepeatedTest(10)` — re-measured at
**12 of 12**, with 2 to 8 of the ten repetitions failing per run, which puts the
single-attempt rate near 50%. It costs ~50ms.

Notably that lever does *not* help the oversell test: its weakness is structural
rather than statistical — only 5 of 500 attempts reach the race window — so ten
repetitions would reach roughly 88%, not certainty. It needed a different test,
not more runs of the same one.

A passing concurrency test is still weak evidence: it shows no breaking
interleaving was *hit*, not that none exists. The real answer is a deterministic
interleaving tool — jcstress or Lincheck — which explores the state space
instead of sampling it.

## Known gaps / what I would do next

1. **Name squatting.** Any authenticated caller can claim the unlimited slot
   for any name. HTTP Basic identifies the API client, not the member, so
   `member_name` is still an identity without proof. A real member id bound to
   the authenticated principal is the fix.
2. **No gateway timeout.** `charge` can block indefinitely; a couple of hundred
   hanging requests would exhaust the servlet pool and take `GET /v1/plans` down
   with it. `PaymentGatewayUnavailableException` exists for exactly this and
   currently has no producer — a bounded timeout would give it one.
3. **Charged with no record.** If the charge succeeds and saving the purchase
   then fails, the unwind releases the slots and key, but the charge cannot be
   reversed: the payment port has no refund or void. The service logs it at
   ERROR with the transaction id. A process crash between the two steps loses
   even that. The production design (authorise/capture, durable state before
   the charge, provider idempotency keys, reconciliation, refund as a saga
   step) is in `docs/ARCHITECTURE.md`.
4. **Request body size is unbounded.** `member_name` is capped at 100
   characters, but the body itself is not; a `OncePerRequestFilter` with a
   content-length limit is the one-line fix.
5. **Member-name normalisation stops at the edges.** Leading and trailing
   invisible space is folded (including non-breaking space — see below), but
   interior spacing, zero-width characters and homoglyphs are not. Properly
   fixing this means a member registry, not more string handling.
6. **Idempotency keys are never evicted.** A real store needs a TTL.

## AI tools used

Claude Code, used as a collaborator throughout: design discussion first, then
implementation against written step plans, with the build and tests run at every
step. Two things it got wrong that the build caught, both recorded in the
commits:

- The plan justified `strip()` over `trim()` with a non-breaking-space example.
  That is **wrong** — Java's `Character.isWhitespace` deliberately excludes
  `U+00A0`, so `strip()` leaves it exactly where `trim()` does. `"Alice"` and
  `"Alice "` would have been different members, making the one-unlimited
  rule bypassable with an invisible character. `MemberKey` now folds on
  `isWhitespace || isSpaceChar`.
- I asserted that only 5 charges could occur under a declining gateway with 5
  slots. Wrong: compensation returns each slot immediately, so ~490 contenders
  each acquire one in turn. The assertion was the bug, not the code.

I can walk through any of this and change it live.
