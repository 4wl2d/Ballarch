# Migrating from Pokeball Core 1.5 to Ballarch Core 2.0

Ballarch was published under the name Pokeball up to Core 1.5. The [previous published Pokeball Core 1.5.0-draft](https://github.com/4wl2d/Pokeball/tree/1bb9e0ecca4d4d1c8b39327c93988c1b490f303e) remains in Git history. Core 2 replaces the project-defined binding with a smaller runtime contract and executable conformance checks. This guide maps the 1.5 vocabulary and each of its 44 laws to [Core 2.0](../spec/ballarch-core-2.0.md).

## What changes for a developer

1. **One decider replaces three roles.** The Interaction/Nucleus/Resource split becomes a pure *decider* (the old Nucleus) plus *adapters* at the edges (the old Interaction and Resource code). The runtime does what 1.5 left to each project's "binding": it serialises inputs, commits atomically, dispatches, tracks deadlines and classifies outcomes.
2. **Three inputs, four outputs.** The six Pulse kinds and seven SemanticOutput kinds collapse into Request, Completion and Notice (inputs) and Call, Reply, Publish and Cancel (outputs).
3. **Outcomes come from the effect class.** In 1.5, a law's trigger (for example "may have executed") decided whether `OutcomeUnknown` applied. In Core 2, the runtime chooses `Done`, `NotDone` or `Unknown` from the call's phase and the effect class recorded with the call at commit (`Safe`, `Idempotent` or `NonIdempotent`). A Safe port never produces `Unknown`.
4. **The runtime makes idempotency keys.** In 1.5 a binding derived an effect's key from `OperationId` and `SemanticHandle`. In Core 2 the runtime derives a key for every call, keeps it for every attempt and re-send, and hands it to the adapter. To repeat an effect after `Unknown`, a decider names the earlier call's *intent* ([Core §3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle)).
5. **No applicability triggers.** Core applies to state that calls Idempotent or NonIdempotent ports; everything else is plain code ([Core §1](../spec/ballarch-core-2.0.md#1-scope)). There is nothing to declare absent.
6. **Fewer rules, each with a named check.** Core 2 has 16 rules, R1–R16 ([Core §5](../spec/ballarch-core-2.0.md#5-kernel-rules)). Security and protocol-design material becomes guidance (§8).

## What came back from 1.5

Several 1.5 ideas return in Core 2 in a smaller form.

| 1.5 | Core 2.0 |
|---|---|
| PBA-30 honest reads, `ReadResult` with a `ConsistencyStamp` | R12: a read returns the last committed state with its revision, and commits nothing. In the Kotlin reference, `Runtime.read` returns a `Snapshot` (id, revision, state), and a typed `watch` delivers one per commit. |
| PBA-40, no mandatory thread hop | §3.1: a runtime may run a step on the thread that submits its input. Inline execution is a scheduler choice, not a profile. The Kotlin reference has `InlineScheduler`. |
| The boundary decision tree and "What is not a Ball" (1.5 §4.4, §4.6) | The [decision guide](decision-guide.md), aligned with where Core applies (§1). |
| PBA-12 ownership epoch or storage fence for a movable owner | R16: a Durable runtime's store excludes a second runtime, or refuses writes from every runtime but the latest. `MemoryStore` fences, and `FileStore` takes an operating-system lock. |
| PBA-14 state kinds | Guidance in the [structure guide](structure.md): business state in a Ball, view state derived, input state in the UI toolkit. |
| PBA-17 stale results by handle | `CallRef` in state names the call whose completion may change what is shown (§8.3). |
| PBA-21 retention covering the retry horizon | A binding's deduplication window, checked at preflight (§3.5). |

## Vocabulary

| Core 1.5 | Core 2.0 |
|---|---|
| Ball, BallInstance | Ball type, Ball instance (`BallId` = type + key) |
| Nucleus (`decide`, pure reads) | decider: `decide`, `complete`, `observe` |
| Interaction | inbound adapter (outside the kernel) plus the request message type |
| Resource / route | adapter port (`AdapterPort`) and its adapter |
| Assembly, manifest | composition root (`Composition`) |
| Pulse: `Intent` | Request. Core 2's *intent* is a different concept (below). |
| Pulse: `ModuleCommandPulse` | Request from another Ball (a call on a `BallPort`) |
| Pulse: `ModuleResultPulse`, `Fact` (resource result) | Completion |
| Pulse: `ObservedSignal` | Notice |
| Pulse: `ControlPulse` (timer, cancellation, control) | Completion of a timer call; a cancellation request is an ordinary Request |
| `ReplyOutput` | the reply plan of `Accept`, or a later `Reply` output |
| `EffectRequest` | `Call` on an adapter port |
| `ModuleCommandRequest`, `ModuleResultOutput` | `Call` on a Ball port, and the target's reply |
| `SignalPublication` | `Publish` |
| `TimerRequest` | `Timer.after` (a call on the built-in timer port) |
| `ProjectionOutput` | none: code outside Balls reads committed state (R12) |
| `CommittedStateSnapshot`, `ConsistencyStamp` | `Snapshot`: the state and its revision, from a read |
| `OutcomeUnknown` | `Outcome.Unknown(reason)` |
| `SemanticHandle` | call key (`CallKey`), call identity (`CallId`), and `CallRef` kept in state |
| `IdempotencyKey` of mutation ingress | idempotency key of an external request, which becomes its request identity (§3.3) |
| `IdempotencyKey` of an `EffectRequest` (from `OperationId` + `SemanticHandle`) | the call's idempotency key, derived by the runtime from the `BallId` and the call key or intent (`Invocation.idempotencyKey`) |
| `OperationId` of an effect requested more than once | intent (`Context.newIntent`), named by every call that requests the effect, on an Idempotent adapter port; or an operation key in the request, on a Ball port (§7.2) |
| `AttemptId` | attempt number (`Invocation.attempt`); the call identity and key stay the same |
| retention horizon of idempotency records | deduplication window of a binding (`dedupWindowMillis`) |
| `RequestId` | request identity |
| `EphemeralState` | UI toolkit state, outside Core |
| `OperationStatus` materializer | status kept in the owner's state and read with a read (§8.5) |
| Read Model Ball | an optional read model built from notices (§8.5) |
| `Draining`, ownership epoch | no draining state; a deploy hands instances over through the store's exclusion or fencing (R16, §6.1) |
| Flow | Flow: a Ball whose state is the workflow (§7.2) |
| Profiles (Inline, SnapshotOutbox, EventJournal, …) | Transient and Durable; event sourcing is an optional representation of Durable (§6); inline execution is a scheduler choice (§3.1) |
| `AdmissionFailure.reason` | `NotDoneReason`, a closed type in the kernel |

## Law-by-law disposition

*Kept* means the obligation is still normative, possibly reworded. *By construction* means the kernel API or the runtime makes a violation impossible or reports it, so no author rule is needed. *Guidance* means the text moved to non-normative §8 or to a guide. *Dropped* means Core 2 no longer makes the claim; the reason is given.

| 1.5 law | Disposition | Core 2 |
|---|---|---|
| PBA-01 Three-Zone Boundary | Replaced | Decider plus adapters (§2); R2, R10 |
| PBA-02 Polar Isolation | By construction | Adapters communicate only through the runtime (R10); module structure §7.1 |
| PBA-03 Pure Bounded Nucleus | Kept | R2; termination is checked by review; output bounds are in R11 |
| PBA-04 Closed Protocol | Guidance / by construction | Message types are the application's own (sealed types recommended); the kernel's outcome and reason types are closed |
| PBA-05 Explicit Decision Inputs | Kept | R2: state, input and context only |
| PBA-06 Controlled Causality | By construction | Outputs exist only in decisions; the runtime executes only committed outputs (R5) |
| PBA-07 Atomic Decision | Kept | R4 |
| PBA-08 Commit Before Dispatch | Kept | R5 |
| PBA-09 No Reentrant Transition | Kept | R3; no synchronous calls between Balls (§3.4) |
| PBA-10 Fault Atomicity | Kept | R4 (a fault commits nothing); quarantine (§3.5) |
| PBA-11 Single Semantic Authority | Kept | R1 |
| PBA-12 Single Writer | Kept | R1, R3, R16: the store excludes or fences a second runtime. Partitioning instances among runtimes and replicating a Ball are outside Core 2 (§1, §6.1) |
| PBA-13 State Isolation | Kept | R10, R12 |
| PBA-14 Explicit State Kind | Guidance | One state per Ball. A value that can change a decision must be in a message or in state. The other state kinds are described in the [structure guide](structure.md) |
| PBA-15 Semantic Handle | By construction | Call keys from `Context.newKey`; `CallId` identifies a call globally; `Context.call` issues a call with its `CallRef` |
| PBA-16 Trusted Identifier Allocation | Kept | R2 and deterministic `Context.newKey`; business identifiers come from messages or are derived from state |
| PBA-17 Revisioned Causality | By construction / guidance | Exactly one completion per call key (R6); stale results are told apart by `CallRef` (§8.3) |
| PBA-18 Provenance-Bound Result | By construction | A completion is delivered only to the instance that committed the call, under its key (R6) |
| PBA-19 ACK/Result Separation | Replaced | `Done` / `NotDone(reason)` / `Unknown(reason)`, whose reasons separate refusal, overload, fault, deadline and ambiguity (§2.2, Appendix C) |
| PBA-20 First-Class Unknown | Kept | R7: `Unknown` only for effectful ports that may have sent; a Safe port ends `NotDone(NoAnswer)`. R15: `Unknown` is never presented as "nothing happened" |
| PBA-21 Explicit Idempotency | Kept | Effect classes; runtime-derived keys and intents (§3.2); deduplication windows (§3.5); idempotent ingress by key with a message-equality check (`IdempotencyConflict`, §3.3) |
| PBA-22 Stable Logical Retry | Kept | R8: retries keep the call identity and its idempotency key; adapters also see the attempt number |
| PBA-23 Cancellation Is a Protocol | Kept | `Cancel` output; the completion still arrives and a cancellation never erases a result (§3.2) |
| PBA-24 Owned Retry Policy | Kept | R8: the runtime is the only retrier, with an optional attempt timeout (§3.2); adapters must not retry |
| PBA-25 Declared Dependency | Kept | R10: composition root; `featuresUseOnlyPublicApis` and `checkModuleBoundaries` in the Kotlin reference |
| PBA-26 Workflow Sovereignty | Kept (SHOULD) | Flow (§7.2) |
| PBA-27 No Wildcard Mediator | Kept | R10: no wildcard routing or handler discovery |
| PBA-28 No Protocol Re-export | Guidance | A feature's public types are its surface (§7.1); not a separate rule |
| PBA-29 Bounded Composition | Kept | R10: the composition root lists every Ball, binding and subscription |
| PBA-30 Honest Read Consistency | Kept | R12: a read returns committed state with its revision and commits nothing; consistency claims fall under §9.5 (Claims) |
| PBA-31 Double Quarantine | Guidance | §8.2 and §8.6: validate at adapters and treat inputs as untrusted |
| PBA-32 Capability-Sealed Effect | Guidance | §8.6: adapters hold capabilities, deciders hold none |
| PBA-33 Dual Gate | Guidance | §8.2: the decider decides permissions; the adapter enforces technical authorization |
| PBA-34 No Ambient Authority | Kept | R2 (no globals in deciders; `noGlobalMutableState`), R10 |
| PBA-35 Safe Sink | Guidance | §8.6 |
| PBA-36 Secret Containment | Guidance | §8.6 |
| PBA-37 Explicit Unsafe Escape Hatch | Dropped | General secure-coding practice, not specific to this architecture |
| PBA-38 Bounded Execution | Kept | R11 and the bounds of §3.5 |
| PBA-39 Profile-Proportional Mechanism | Replaced | Proportionality by construction: Core applies only to state that calls Idempotent or NonIdempotent ports (§1) |
| PBA-40 Zero Mandatory Runtime Tax | Kept (reduced) | No mandatory thread hop or handler discovery (§3.1, R10). A runtime remains, and under the Durable profile serialisable state and a store; costs in §10 |
| PBA-41 Measured Claim | Kept | §9.5 Claims |
| PBA-42 Honest Guarantee Scope | Kept | R13, §9.5 |
| PBA-43 Foundation Quarantine | By construction | Shared code holds no mutable state (`noGlobalMutableState`); features share only public types (R10) |
| PBA-44 Trusted Actor Context | Kept (reduced) | `RequestContext.caller`, a principal authenticated by the inbound adapter (§2.3, §8.2) |

**Summary.** Of the 44 laws, 26 are kept or kept in reduced form, 6 are enforced by construction, 8 became guidance, 3 were replaced by a different mechanism, and 1 was dropped (PBA-37). The counts assign each law to its first-listed disposition.

## Agent skills

The 1.5 skills `pokeball`, `pokeball-async`, `pokeball-binding`, `pokeball-composition` and `pokeball-review` are not carried over: they apply the 1.5 laws (PBA-01 to PBA-44) and vocabulary. Remove them from your agent and install the single Core 2 skill in [`skills/ballarch/`](../skills/ballarch/SKILL.md).

## Porting a feature

1. Keep the old Nucleus logic as the body of `decide`. Replace pulse-kind branching with one `when` over the request type, plus `complete` for completions and `observe` for notices.
2. Replace each `EffectRequest` with a call on an `AdapterPort`, made with `ctx.call`, and keep its `CallRef` in state. Choose the port's effect class honestly in the composition root. Move the transport code into an `Adapter` that passes `Invocation.idempotencyKey` to the destination, and check it with the adapter conformance kit.
3. Replace an `OperationId` that guards a repeated effect with an intent from `ctx.newIntent()`, kept in state and named by every call that requests the effect. Declare the destination's deduplication window on the binding.
4. Replace `ModuleCommandRequest` with a call on the target's `BallPort`. Replace `SignalPublication` with `Publish` plus a subscription in the composition root.
5. Delete handle, status-materializer and trigger-absence code. Keep status in state and let screens and endpoints get it with `Runtime.read` or `watch`.
6. Handle each call's single completion in `complete` with `outcomeFor`: `Done`, `NotDone` and, for effectful ports, `Unknown`. After `Unknown`, repeat on an Idempotent port with the same intent, or declare reconciliation on the binding (§8.4). Never repeat a NonIdempotent effect blindly (R15).
