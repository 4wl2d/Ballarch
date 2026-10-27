# Problems Ballarch addresses, and problems it does not

Fifteen recurring problems, from apps to drivers. For each: where it shows up, the Ballarch mechanism and rule that address it, the evidence in this repository, and the novelty verdict of [Core §12](../spec/ballarch-core-2.0.md#12-lineage-and-what-is-new-informative). Most mechanisms are established practice.

Web sources were accessed on 2026-10-27, and statements about products are as of that date. Every result about the references is asserted by the test named with it.

## Problems addressed

### P1. An uncertain outcome is reported as a plain error

- **Seen in** (accessed 2026-10-27): Elm's `Http.Error` has `Timeout` and `NetworkError`, and neither says whether the server processed the request ([elm/http](https://github.com/elm/http/blob/master/src/Http.elm)). Riverpod mutations end in error or success ([Riverpod](https://riverpod.dev/docs/concepts2/mutations)). An MCP tool result carries a boolean `isError` ([MCP](https://modelcontextprotocol.io/specification/2025-03-26/server/tools)). Stripe, by contrast, says to treat a 500 "as indeterminate" ([Stripe](https://docs.stripe.com/error-low-level)).
- **Mechanism:** every call ends `Done`, `NotDone` or `Unknown`, chosen by the runtime from the call's phase and its recorded effect class. `NotDone` needs evidence. With `CallRef` and `outcomeFor`, the `Unknown` branch is part of the decider's type. A screen never shows `Unknown` as failed ([honest UI](honest-ui.md)).
- **Rule:** R7, R15 ([Core §3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle)).
- **Evidence here:** trace property T7 checks every explored run against ground truth. `PlaceOrderTest`: `under every fault and crash point no order is placed twice, and no Unknown is shown as NotPlaced`.
- **New?** The third outcome is old (RPC's "maybe", Jepsen's `:info`). One runtime rule over every port, delivered as a typed completion, is a new combination (§12, row 2).

### P2. Retries are on by default and ignore whether a repeat is safe

- **Seen in** (accessed 2026-10-27): Temporal's default activity retry policy has unlimited attempts ([Temporal](https://docs.temporal.io/encyclopedia/retry-policies)). Yuan et al. found that simple tests of error-handling code would have prevented most catastrophic failures they studied ([OSDI 2014](https://www.usenix.org/conference/osdi14/technical-sessions/presentation/yuan)).
- **Mechanism:** each port declares an effect class. Only the runtime retries, only Safe and Idempotent ports, within a finite budget, with an optional attempt timeout. The composition refuses retries on a NonIdempotent port. Adapters never retry.
- **Rule:** R8 ([Core §5.2](../spec/ballarch-core-2.0.md#52-runtime-rules)).
- **Evidence here:** `RuntimeTest`: `non-idempotent ports cannot be configured with retries`. Trace property T8, Alloy P4, and seeded re-send bugs in C (`BA_MUTANT_RESEND_AFTER_CRASH`) and RTL (`MUTANT_RETRY_FIRE`). Adapter check C2 catches "broken: retries internally" in `HttpAdapterConformanceTest`.
- **New?** Not new as an annotation (HTTP methods, protobuf `idempotency_level`, MCP hints). Refusing retries at composition and deriving outcomes from the class belong to the row 2 combination. There is no retry budget across calls ([Core §8](../spec/ballarch-core-2.0.md#8-guidance-non-normative), 8.4).

### P3. A crash window between an effect and its record, and hand-made keys

- **Seen in** (accessed 2026-10-27): Inngest: "A request can succeed before Inngest records the step result" ([Inngest](https://www.inngest.com/docs/guides/handling-idempotency)). Stripe keeps keys for at least 24 hours and may then remove them, so a reused key can execute a new request ([Stripe](https://docs.stripe.com/api/idempotent_requests)). NServiceBus asks that outbox retention exceed the longest retry ([NServiceBus](https://docs.particular.net/nservicebus/outbox/)).
- **Mechanism:** the runtime saves a write-ahead record before the first send. It derives one idempotency key per call, kept by every attempt and re-send, and never repeated after a Transient restart. An intent gives a repeat after `Unknown` the earlier call's key. Preflight refuses a call whose deadline outlives the destination's declared deduplication window.
- **Rule:** R5, R8, keys and intents ([§3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle)), windows ([§3.5](../spec/ballarch-core-2.0.md#35-bounds-and-preflight)), adapter check C5 ([Appendix D](../spec/ballarch-core-2.0.md#appendix-d-adapter-checks-normative)).
- **Evidence here:** scenarios S4b, S8 and S9 in `ExplorationTest`, with negative controls. An adapter that makes "a key of its own per attempt" fails C5. `RuntimeRulesTest`: `a call is durably recorded as possibly sent before its adapter runs`.
- **New?** Writing ahead of the send is not new. Runtime-derived keys, intents, a window checked at preflight and optional reconciliation are a modest new combination (row 3).

### P4. In-flight work is tied to a screen or a task

- **Seen in** (accessed 2026-10-27): RIBs: "All operations performed by the Interactor must be confined to its lifecycle" ([RIBs](https://github.com/uber/RIBs/wiki)). Android `SavedStateHandle` updates "might be lost" if the process is killed in the background ([Android](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate)). In async Rust, "A future, on being dropped, is cancelled" ([Oxide RFD 400](https://rfd.shared.oxide.computer/rfd/400)).
- **Mechanism:** obligations belong to a Ball, not to a screen, and the runtime lives as long as the application. The UI reads with `read` and `watch`, which commit nothing. Under the Durable profile the call ledger survives a crash, and recovery handles each call by its recorded class ([structure](structure.md)).
- **Rule:** R6, R12, recovery ([§3.7](../spec/ballarch-core-2.0.md#37-crash-and-recovery)).
- **Evidence here:** `FileStoreTest`: `a possibly sent non-idempotent call ends Unknown after a restart from disk and is not sent again`. `ShopAppTest`: `a screen reads the checkout status, which commits nothing`.
- **New?** Not new: durable entities and actors. The tree has no mobile store; `FileStore` is tested against killed processes, not power loss.

### P5. In drivers, a cancel, timeout or reset is taken as proof that nothing happened

- **Seen in** (accessed 2026-10-27): a Microchip SAMA7G5 erratum: an MCAN cancel can be signalled as successful while "the transmission continues until the complete frame has been sent" ([Microchip](https://onlinedocs.microchip.com/oxy/GUID-CFE060D6-6076-46C0-B8FD-60B6DA382A42-en-US-8/GUID-C02B5019-9536-43EE-BCA4-AA8D209A1A80.html)). A 2018 linux-nvme thread quotes driver code that polls the completion queue on a timeout: "Did we miss an interrupt?" ([linux-nvme](https://lists.infradead.org/pipermail/linux-nvme/2018-May/017516.html)).
- **Mechanism:** the send horizon is a defined instant, such as a `valid && ready` handshake. After it, `NotDone` needs evidence covering every attempt; a cancel acknowledgement is not evidence. An adapter captures evidence before a reset and polls the completion source before reporting `Failed`.
- **Rule:** R7, the horizons ([§2.5](../spec/ballarch-core-2.0.md#25-boundaries-in-time)), C6.
- **Evidence here:** the RTL bench catches `MUTANT_WITHDRAWN_RETRY_NOT_DONE`; the firmware exploration catches `BA_MUTANT_NACK_ALWAYS_CONCLUSIVE`. C6 is checked by review only.
- **New?** The driver idioms are established. One outcome rule for services, firmware and RTL is a narrow combination (row 4). See [levels](levels.md).

### P6. Layering adds ceremony

- **Seen in** (accessed 2026-10-27): Google's Android guide calls the domain layer optional and warns that forcing use cases "can add complexity for little benefit" ([Android](https://developer.android.com/topic/architecture/domain-layer)).
- **Mechanism:** Core applies only to state that calls Idempotent or NonIdempotent ports. Rendering, caches, Safe reads, mappers and CRUD in one transaction stay plain code. A feature is one package; no use case or repository is required.
- **Rule:** where Core applies ([§1](../spec/ballarch-core-2.0.md#1-scope)); module structure ([§7.1](../spec/ballarch-core-2.0.md#71-module-structure)).
- **Evidence here:** the [place-order example](../reference/kotlin/examples/place-order/) is one Ball file, its binding and its test. Nothing here measures code size or effort.
- **New?** Adoption value, not research. See [compared with Clean](compared-with-clean.md) and the [decision guide](decision-guide.md).

### P7. Coding agents degrade under accumulated constraints and prose rules

- **Seen in** (accessed 2026-10-27): agent performance declines as structural requirements accumulate ([arXiv:2605.06445](https://arxiv.org/abs/2605.06445)). Repository context files do not generally improve task success, and they raise inference cost ([arXiv:2602.11988](https://arxiv.org/abs/2602.11988)). An agent with access to unit tests may delete failing tests instead of fixing the bug ([arXiv:2510.20270](https://arxiv.org/abs/2510.20270)).
- **Mechanism:** the rules that matter are checks, not prose: decider purity, composition refusals, exhaustive outcome matching and trace oracles. Oracles stay outside the write scope of automated changes.
- **Rule:** R2, R10, testing guidance (§8.7).
- **Evidence here:** `BallarchRulesTest` runs each architecture rule against seeded violations. No evaluation of agents that use Ballarch exists here, and the [agent skill](../skills/ballarch/SKILL.md) is not yet evaluated.
- **New?** Not new: architecture tests and checks around agents. No claim of benefit. See [agents](ai-agents.md).

### P8. Agents overclaim success, and tool metadata is only a hint

- **Seen in** (accessed 2026-10-27): in most episodes in which agents had duplicated an effect, they still reported success ([arXiv:2609.29095](https://arxiv.org/abs/2609.29095)). MCP clients "MUST consider tool annotations to be untrusted" unless the server is trusted ([MCP](https://modelcontextprotocol.io/specification/2025-03-26/server/tools)). The OpenAI Agents SDK: approval "does not guarantee exactly-once execution" ([OpenAI](https://openai.github.io/openai-agents-python/human_in_the_loop/)).
- **Mechanism:** the application declares each tool port's class on the trusted side; a hint never replaces that. Keys come only from the runtime. R14 bounds a task's final status by the knowledge of its calls; a model's text cannot raise it.
- **Rule:** [§2.6](../spec/ballarch-core-2.0.md#26-declaring-effect-classes), R8, R14, R15.
- **Evidence here:** `KnowledgeTest` and the trip exploration (P12). The tree has no reference agent code.
- **New?** A model as an advisor behind deterministic policy is not new. Joining it to a final report computed by the algebra is a combination.

### P9. Code changes while work is open

- **Seen in** (accessed 2026-10-27): Temporal workflows on auto-upgrade "need to be kept replay-safe manually" ([Temporal](https://docs.temporal.io/worker-versioning)). Inngest runs a renamed step again ([Inngest](https://www.inngest.com/docs/learn/versioning)). During a gradual deployment, Cloudflare Durable Objects run different versions ([Cloudflare](https://developers.cloudflare.com/workers/versions-and-deployments/gradual-deployments/with-durable-objects/)).
- **Mechanism:** no replay. The runtime stores state and the call ledger and classifies each call by its recorded class. A Ball type has a state version and `migrate`. A runtime refuses to start on records it cannot serve. Exclusive ownership hands instances from one runtime to the next.
- **Rule:** R4, R16, evolution ([§6.1](../spec/ballarch-core-2.0.md#61-evolution)).
- **Evidence here:** `EvolutionTest`: `a call is classified by the effect class recorded at commit, not by the port's binding after a restart`. `OwnershipTest`: four tests, one with a second process.
- **New?** Not new: recovery without replay. Its limits are in [§10](../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations).

### P10. Matching results, ordering and interleaving are left to application code

- **Seen in** (accessed 2026-10-27): in Swift, "Actor-isolated functions are reentrant", so other work interleaves at each `await` ([SE-0306](https://github.com/apple/swift-evolution/blob/main/proposals/0306-actors.md)). Orleans grains that call each other may deadlock ([Orleans](https://learn.microsoft.com/en-us/dotnet/orleans/grains/request-scheduling)).
- **Mechanism:** one step at a time per instance. No Ball waits for another, so a call cycle cannot deadlock or re-enter a decider. Each call gets one completion, delivered to the instance that committed it. A `CallRef` in state names the completion that may change the screen.
- **Rule:** R3, R6, [§3.4](../spec/ballarch-core-2.0.md#34-ball-to-ball-calls).
- **Evidence here:** trace properties T3 and T6; `InlineSchedulerTest`; `CatalogSearchTest`: `only the latest search changes the display, which a typed watch shows`.
- **New?** Not new: actors, Elm, The Composable Architecture.

### P11. Double submission is solved again on every screen

- **Seen in** (accessed 2026-10-27): `bloc_concurrency` offers `droppable` and `restartable` event transformers ([pub.dev](https://pub.dev/packages/bloc_concurrency)). Each framework ships its own policy.
- **Mechanism:** the Ball's stage decides. A second "place" request meets `Placing` and joins it. An external request with the same idempotency key joins or replays the first. Latest-wins is a `Cancel` plus a `CallRef` check. There is no admission DSL.
- **Rule:** R1, inbound requests ([§3.3](../spec/ballarch-core-2.0.md#33-inbound-request-lifecycle)).
- **Evidence here:** `PlaceOrderTest`: `decide places a new order, joins a double tap, and repeats an unknown one with its intent`. `RuntimeTest`: `same key with a different message is an idempotency conflict`.
- **New?** Adoption value, not research.

### P12. Long-running flows claim success or failure after a partial effect

- **Seen in** (accessed 2026-10-27): a flow spread over event notifications is "not explicit in any program text" ([Fowler](https://martinfowler.com/articles/201701-event-driven.html)). Oxide: a saga "may unwind having left an instance running", and a failing undo leaves it `Stuck` ([Oxide RFD 704](https://rfd.shared.oxide.computer/rfd/0704)).
- **Mechanism:** a Flow is one Ball whose state is the workflow. An (all, any) knowledge value over Kleene's three-valued logic bounds what it may claim. Calls it may still make count as `Maybe`; only absorbing compensations count as undoing. A settled Flow replies with its knowledge, like one honest call.
- **Rule:** R14, knowledge ([§4.2](../spec/ballarch-core-2.0.md#42-knowledge-of-composite-operations)), closure ([§7.4](../spec/ballarch-core-2.0.md#74-composition-closure)).
- **Evidence here:** `KnowledgeTest` checks soundness and exactness on 69,905 worlds. `TripFlowTest`: in 6,438 runs no status or settled claim contradicted the bookings, while a two-valued status was wrong in 1,190; without the providers' fence, 115 of 7,257 settled runs end with a false claim. `ItineraryFlowTest`: no false claim in 29,774 runs with faults and crashes, or 130,321 without crashes.
- **New?** A new application of a classical structure: the lead claim (row 1).

### P13. Lossy and duplicated notifications are handled as exactly-once

- **Seen in** (accessed 2026-10-27): errors in the last bits of a CAN frame can cause inconsistent delivery and duplicates (Rufino et al. 1998, [abstract](https://www.dpss.inesc-id.pt/~ler/pubs/canamp.html)). AUTOSAR E2E detects `REPEATED` and `OKSOMELOST` data with a counter ([AUTOSAR](https://www.autosar.org/fileadmin/standards/R22-11/FO/AUTOSAR_PRS_E2EProtocol.pdf)).
- **Mechanism:** a call has one completion; late reports are dropped. Notices from one publisher are applied in order, at most once. An interrupt is a hint to read status, not an outcome.
- **Rule:** R6, notices ([§3.6](../spec/ballarch-core-2.0.md#36-notices)), C6.
- **Evidence here:** S6 (`notices are delivered exactly once in order across a crash anywhere`); trace property T6; the RTL bench catches `MUTANT_NO_TAG_CHECK` and `MUTANT_DOUBLE_COMPLETION`.
- **New?** Not new: sequence counters and tags. The same rule at three levels is part of row 4.

### P14. Offline write queues have no outcome semantics

- **Seen in** (as of 2026-10-27): Store5's Updater leaves a failed write "in the queue for future retries", and its documentation does not say whether that write may already have been applied ([Store5](https://store.mobilenativefoundation.org/docs/concepts/store5/updater)).
- **Mechanism:** runtime retries cover a short outage before the call's deadline, which must fit the window. A long outage keeps the intent in a queued stage. After `Unknown`, the Ball repeats with the same intent inside the window and reconciles beyond it.
- **Rule:** intents (§3.2), windows (§3.5), R15.
- **Evidence here:** `PlaceOrderTest`: `a week later the deduplication window refuses to try again, and nothing is sent`.
- **New?** Part of the modest row 3 combination. Merging offline edits is out of scope.

### P15. Invariants across owners, and module boundaries that drift

- **Seen in** (accessed 2026-10-27): in the DCB course example, a capacity rule across aggregates needs a saga and records one subscription twice ([DCB](https://dcb.events/)). A Packwerk retrospective: violations grew with every feature, and a package with none still did not work in isolation ([Rails at Scale](https://railsatscale.com/2024-01-26-a-packwerk-retrospective/)).
- **Mechanism:** module boundaries are R10 checks in the build. Cross-owner invariants have no mechanism: see below.
- **Rule:** R10.
- **Evidence here:** `featuresUseOnlyPublicApis` and `checkModuleBoundaries` in the Kotlin build.
- **New?** Not new: architecture tests.

## Problems Ballarch does not solve

- **Offline merges** of concurrent edits are a non-goal ([§1](../spec/ballarch-core-2.0.md#1-scope)).
- **Invariants that span owners** need a coordinating Ball, a reservation or a storage constraint.
- **Destinations that break their declared horizon.** A status read after the delivery horizon is trusted. The S10 negative control in `ReconciliationTest` must find a false `NotDone` from a destination that applies a request after its horizon.
- **Adapter honesty and declarations.** Checks C1–C5 run against a simulated destination. C6, real response mapping, ownership boundaries, effect classes, windows and horizons need review.
- **Consensus, replication and hot keys.** One owner is one sequential writer.
- **Exactly-once effects.** Effectively-once needs a deduplicating destination or reconciliation.
- **Knowledge across a Transient crash.** In `TripFlowTest` (`the trip requires the Durable profile`), a client that books again after a restart gets a false settled claim in 608 of 3,073 settled runs, and 152 runs book a flight twice.
- **Exclusion across hosts.** The file store's lock covers one host.
- **Levels together.** No check covers firmware behind a service port.
- **Other languages.** Reference code exists in Kotlin, C and SystemVerilog. Swift, Rust and TypeScript are sketches ([bindings](bindings.md)).
- **Its own costs.** Under the Durable profile one effectful call answered `Done` takes three durable writes (`RuntimeTest`). A dedicated loop adds a thread hand-off per input. Productivity, code size and agent benefit are unmeasured.
