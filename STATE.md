# STATE

Durable mirror of the Current State Anchor. Read first, write last.

```
==================================================
CURRENT PHASE: Post-execution remediation -> Round 2 (refinements) complete
LAST APPROVED: Round 2 plan — idempotency ordering + original-response replay,
               blank/null validation, payment limitation, docs consolidation, PORT
NEXT STEP: Awaiting User Input — push development, or further changes
==================================================
```

**Branch:** `development` (tracks `origin/development`), merges to `main` when
remediation is done. Assessment: `firstphasetest.md`. Fixes M1-M4 are applied
one per commit, each approved before it is written.

| Fix | Closes | Status |
|---|---|---|
| M1 generic payment `detail` | P2-1 | ✅ Done — 145 tests green; runtime F-01L..07L pass |
| M2 strict `start_date` | P2-2 | ✅ Done — 147 tests green; runtime V-14..19, V-28/29 pass |
| M3 one name-normalisation path | P2-3/4/5 | ✅ Done — 160 tests green; runtime V-09, V-38, U-11 pass; D-4 now NFC + trim + case-fold |
| M4 non-vacuous persistence test | P3-8 | ✅ Resolved 2026-11-02 by removing the vacuous test (round 2, item 6) |

**Round 2 (2026-11-02, branch `remediation/02-refinements`):** idempotency key
claimed before date/plan checks; replay returns the stored original `Purchase`
(not current state); whitespace-only `member_name` is a field error; JSON `null`
body pinned as 400; orphaned successful charge logged at ERROR; `server.port:
${PORT:8080}` plus `.ebextensions` for Beanstalk; README/SUBMISSION corrected;
phase docs and `context/` consolidated into `docs/ARCHITECTURE.md` (originals in
history at `542ec1f`).

## Build health

| Check | Result |
|---|---|
| `./gradlew clean test` | **181 tests, 0 failures** (2026-11-02) |
| Live end-to-end sweep | Round 2: null body, blank name, replay-after-cancel, key reuse, PORT 8080/5000 all pass |
| Concurrency suite repeatability | 10 consecutive clean runs, no flakes |
| Vendor files byte-identical | `SeedData`, `AlwaysSucceedingPaymentGateway`, `MembershipsApplication`, `package-info`, `build.gradle.kts`, `settings.gradle.kts` |
| Intentional vendor edits | `PaymentGateway.java` +25; `application.yml` extended, and `server.port` now `${PORT:8080}` (was a fixed value) |
| New dependencies added | none |

## Execution history

| Milestone | Branch | Steps | Merge | Delivered |
|---|---|---|---|---|
| 1 | `impl/01-foundation` | 01–03 | `94a501d` | Domain, ports, adapters, seeding, `GET /v1/plans` |
| 2 | `impl/02-purchase-flow` | 04–06 | `91954a8` | Payment taxonomy, `PurchaseService`, endpoints + error table |
| 3 | `impl/03-hardening` | 07–08 | `919f4bb` | Concurrency harness, integration tests, `SUBMISSION.md` — **core complete** |
| 4 | `impl/04-stretch` | 09–10 | `d491537` | Cancel with restore, `Idempotency-Key` |

Core shipped and merged before any stretch branch opened, so `main` held a
complete submittable solution from `919f4bb` onward.

## Findings from execution

| ID | Finding | Resolution |
|---|---|---|
| **DV-2** | Step 01 justified `strip()` over `trim()` with a non-breaking-space example. **Wrong** — `Character.isWhitespace` excludes `U+00A0`, so `strip()` leaves it exactly where `trim()` does. `"Alice"` vs `"Alice "` would have been different members, making the one-unlimited rule bypassable with an invisible character | `MemberKey` folds on `isWhitespace \|\| isSpaceChar`; recorded in `step_01` §7 |
| **DV-3** | **Mutation testing measured over repeated runs, not assumed.** Broken inventory CAS: caught **3 of 16** by the oversell test alone (~19%), **5 of 5** by the full suite; counter inflated to 6–20, above its seeded max. Broken claim store: **9 of 12** (75%). Cancel restore-before-CAS: **5 of 5**, with 2–6 callers each cancelling one purchase | Recorded in `step_07` §8 and `SUBMISSION.md`. Retrospectively justifies `release()` not clamping: a clamp would have *inverted* the result, surviving 5/5 instead of dying 5/5 |
| **DV-4** | **Correction to my own earlier claim.** I wrote that the claim-store mutant "dies immediately" and far more readily than the inventory one. One observation; a 5-run sample then killed it only 1 of 5, and a 12-run sample settled at 75% — the weakest detection in the suite | Figures corrected in `step_07` §8 and `SUBMISSION.md`, then **fixed**: `@RepeatedTest(10)` on `sameMemberWinsExactlyOneUnlimitedMembership`, re-measured at **12 of 12** |
| — | Boot 4 relocated `@WebMvcTest` to `org.springframework.boot.webmvc.test.autoconfigure`, and Jackson autoconfiguration to the `spring-boot-jackson` module | Verified against the resolved classpath before writing code |
| — | My own test asserted `charges() == 5` under a declining gateway. Wrong: compensation returns each slot immediately, so ~490 contenders each acquire one | Corrected to the real invariant, with the wrong mental model documented in the test |
| — | `PurchaseControllerTest` broke when the 4-arg overload landed — the mock returned `null` and every mapped error became a 500 | Caught by the build; stubs updated and new coverage added |

## Scope delivered

Tier 0 (core) + Tier 1 (error codes) + Tier 2 (cancel) + Tier 3 (idempotency).
All four tiers.

## Known gaps, deliberate

Documented in `SUBMISSION.md`: name squatting needs authentication; no gateway
timeout; charged-with-no-record under in-memory storage; unbounded request body;
member-name normalisation stops at the edges; idempotency keys are never
evicted.
