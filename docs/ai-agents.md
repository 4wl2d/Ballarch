# Ballarch and AI agents

This guide covers two uses. A **coding agent** writes or changes Ballarch code. In an **agent system**, a language model proposes actions and tools carry them out; here it is built from Balls and ports. Nobody has measured whether Ballarch helps either kind of agent. This guide claims no such benefit ([Core §10](../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)).

A sketch has no reference code behind it. Other code is copied from the source file named above it.

## 1. Coding agents and legibility

A Ballarch feature keeps the facts an agent needs in one place:

- **One file per Ball.** Ports, stages, `decide` and `complete` sit together. See [`PlaceOrder.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt). Its binding, with retries, window and reconciliation, is in the `app` sub-package: [`PlaceOrderApp.kt`](../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/app/PlaceOrderApp.kt).
- **Sealed stages.** Each stage of an operation is a type, including the one in which nobody knows yet (`Checking` in the place-order example).
- **Exhaustive outcomes.** `complete` matches `Done`, `NotDone` and `Unknown` with no catch-all branch, so the compiler reports a missing case.
- **`CallRef` in state.** `completion.outcomeFor(ref)` returns `null` for every other call, so a late or superseded completion cannot change the wrong stage.
- **The floor.** Code that calls no Idempotent or NonIdempotent port is plain code ([Core §1](../spec/ballarch-core-2.0.md#1-scope)). There are no use cases, repositories or per-layer models to scaffold.

Three studies motivate this layout. None of them tested Ballarch.

- Dente, Satriani and Papotti, [*Constraint Decay: The Fragility of LLM Agents in Backend Code Generation*](https://arxiv.org/abs/2605.06445), arXiv:2605.06445 (accessed 2026-10-27). On multi-file backend tasks, agents' assertion pass rates fell as structural constraints were added: a required Clean Architecture layering, a database and an ORM. In their per-constraint analysis (§5.1), the layering lowered pass rates, and the database constraints lowered them most.
- Zhu, Tsantalis and Rigby, [*AI-Generated Smells: An Analysis of Code and Architecture in LLM and Agent-Driven Development*](https://arxiv.org/abs/2605.02741), arXiv:2605.02741 (accessed 2026-10-27). They report that more capable models generate more bloated and coupled code. In the multi-file repositories that an agent framework generated for the study, they describe a "modular mirage" (§4.2.4): the code is split into files, but related logic is scattered across them.
- Gloaguen et al., a study of repository-level context files for coding agents, [arXiv:2602.11988](https://arxiv.org/abs/2602.11988) (accessed 2026-10-27). Context files did not generally improve task success, and they raised inference cost.

Whether Ballarch's layout changes these results is unknown. The [agent skill](../skills/ballarch/SKILL.md) is not yet evaluated.

## 2. Sensors

Böckeler's [*Harness engineering for coding agent users*](https://martinfowler.com/articles/harness-engineering.html) (accessed 2026-10-27) separates guides, which steer an agent before it acts, from sensors, which check its work after. Ballarch offers these sensors:

| Sensor | What it catches | Where it exists |
|---|---|---|
| Decider purity | I/O, clocks, randomness, threads or runtime use in a decider (R2) | `deciderPurity` (ArchUnit) in the Kotlin reference; [`check_purity.sh`](../reference/c/tools/check_purity.sh) for C. In Rust, a decider crate that depends only on the kernel crate (no reference). |
| Composition refusals | A retry policy or attempt timeout on a NonIdempotent port; a deduplication window on a port that is not Idempotent; reconciliation on a Safe port | `Composition.adapter` throws when the composition is built |
| Preflight faults | An intent on a port that is not an Idempotent adapter port; a deadline beyond the deduplication window | The runtime refuses the decision ([Core §3.5](../spec/ballarch-core-2.0.md#35-bounds-and-preflight)) |
| Exhaustive match | A missing `Unknown` branch | A `when` or `switch` with no default branch over the outcome type |
| Trace checker | Violations of T3 and T5–T9 in conformance traces | [`tools/oracles.py`](../tools/TRACE-FORMAT.md#checking-a-trace), run in CI |
| Adapter checks | An adapter that retries, reports twice, claims `NotApplied` without evidence or invents keys (C1–C5) | `AdapterConformance` in `ballarch-testkit` |
| Module boundaries | A feature that imports another feature's implementation | `featuresUseOnlyPublicApis`, `checkModuleBoundaries` |

The composition refusals, from `reference/kotlin/ballarch-runtime/src/main/kotlin/ballarch/runtime/Composition.kt`:

```kotlin
        require(retry.maxAttempts == 1 || port.effect != EffectClass.NonIdempotent) {
            "port '${port.name}' is NonIdempotent and cannot be retried"
        }
        require(retry.attemptTimeoutMillis == null || port.effect != EffectClass.NonIdempotent) {
            "port '${port.name}' is NonIdempotent and cannot have an attempt timeout"
        }
```

A sensor works only if the agent cannot change it. Zhong, Raghunathan and Carlini, [*ImpossibleBench*](https://arxiv.org/abs/2510.20270), arXiv:2510.20270 (accessed 2026-10-27), measure how often agents pass tasks that cannot be solved, for example by changing the tests. Keep oracles, conformance tests, explorations and architecture rules outside the agent's write scope ([Core §8.7](../spec/ballarch-core-2.0.md#8-guidance-non-normative)). Pin them, or run them from the main branch in CI, and review each change to them as a specification change.

## 3. Agent systems: the shape

An agent run is a Flow: one Ball owns it ([Core §7.2](../spec/ballarch-core-2.0.md#72-workflows)). Its state holds:

- the **goal**, from the user, trusted;
- the **plan** or the model's latest proposal, untrusted;
- the **tool ledger**: for each tool call, its `CallRef`, its intent and its outcome;
- the **approvals**, granted and pending.

**The model is an adapter port.** A call that only returns text changes nothing at the destination, so declare that port Safe. It may then be retried, and it never ends `Unknown`. A repeat costs tokens, not effects. A hosted model that runs tools on its own is not Safe. Declare it NonIdempotent, or turn its hosted tools off and run every tool through a port.

**Streaming.** A streamed answer is still one completion. The adapter reads the stream to its end and reports once. A stream cut off part-way is `Failed`, so the runtime retries the Safe call from the start within its attempt budget, or ends it `NotDone(NoAnswer)`. Parse tool calls that arrive as deltas only from the complete answer, so a partly streamed tool call is never made. The adapter may pass tokens to the UI as they arrive; they are display data, not Ball state, and decide nothing. Several tool calls in one answer become several calls in one decision, each with its own `CallRef` in the tool ledger.

**A committed answer is not asked again.** The model's answer arrives as a completion and is committed with the decision that handles it. After a crash, a Durable runtime restores committed state and outputs. It does not re-run deciders to regenerate them (R4), so the model is not asked again for an answer the Ball already has. A model call still in flight at the crash may be sent again, as any Safe call may ([Core §3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle)).

**Policy turns proposals into calls.** The answer is untrusted input ([Core §8.6](../spec/ballarch-core-2.0.md#8-guidance-non-normative)). `complete` parses it into a proposal. Deterministic code in the decider checks the tool, the arguments, the budget and the approvals, and only then makes a call. The model never emits a call itself. This follows CaMeL (Debenedetti et al., [arXiv:2503.18813](https://arxiv.org/abs/2503.18813), accessed 2026-10-27), in which untrusted data cannot change the program's control flow, and the patterns of Beurer-Kellner et al. ([arXiv:2506.08837](https://arxiv.org/abs/2506.08837), accessed 2026-10-27). Ballarch does not give their security guarantees by itself. It gives the policy one place: a pure function you can test.

Sketch: no reference code and no conformance claim.

```kotlin
// in complete(), while the Ball waits for the model
is Asking -> when (val answer = completion.safeOutcomeFor(state.ask)) {
    is Outcome.Done -> next(state, Proposal.parse(answer.value), ctx)  // model text becomes data
    is Outcome.NotDone -> finish(state.withNote("model unavailable"))
    null -> step(state)                                                // another call's completion
}

fun next(state: Asking, p: Proposal?, ctx: Context): Step<AgentState, TaskReport> = when {
    p !is Proposal.UseTool -> finish(state)
    p.tool !in state.goal.allowedTools -> ask(state.refused(p), ctx)
    needsApproval(p.tool, state) -> requestApproval(state, p, ctx.newIntent(), ctx)
    else -> useTool(state, p, ctx.newIntent(), ctx)
}
```

## 4. Tools are ports

Each tool is an adapter port. Its effect class is declared in code the application owns. A tool's own description informs that declaration but never replaces it ([Core §2.6](../spec/ballarch-core-2.0.md#26-declaring-effect-classes)).

As of 2026-10-27, the [MCP specification (2026-07-28)](https://modelcontextprotocol.io/specification/2026-07-28/server/tools) requires clients to treat tool annotations as untrusted unless they come from trusted servers. Its [schema](https://modelcontextprotocol.io/specification/2026-07-28/schema) calls every annotation a hint. It defines `idempotentHint` as: repeated calls with the same arguments have no additional effect.

| What the tool declares | Declare the port | Why |
|---|---|---|
| No annotation, or an untrusted server | NonIdempotent | A class that has not been established is NonIdempotent (§2.6). |
| `readOnlyHint: true`, from a server you reviewed | Safe, declared in your own code | The hint informs; your declaration decides. A read that changes the destination, such as marking a message read, is not Safe. |
| `idempotentHint: true`, with a key argument the server deduplicates | Idempotent; the adapter fills that argument with `invocation.idempotencyKey` | Ballarch's Idempotent means deduplication by the key the runtime supplies ([Core §2](../spec/ballarch-core-2.0.md#2-model)). |
| `idempotentHint: true`, without such an argument | NonIdempotent | "Same arguments" does not stop a delayed attempt from applying after a later change. |

A tool adapter reports as any adapter does ([Appendix D](../spec/ballarch-core-2.0.md#appendix-d-adapter-checks-normative)):

- a result without `isError` → `Ok(value)`;
- `isError: true` → `Failed`, unless the server's reviewed contract says that error applied nothing; then `Refused` (C3);
- a task whose status can no longer be read, or a timeout or dropped connection after the request was sent → `Failed`;
- a connection refused before the request was written → `NotApplied`, with that evidence.

The runtime turns `Failed` into a retry, or into `Unknown` on an effectful port and `NotDone(NoAnswer)` on a Safe one. The adapter contract, from `reference/kotlin/ballarch-runtime/src/main/kotlin/ballarch/runtime/Adapter.kt`:

```kotlin
 * Contract (checked by the adapter conformance kit in `ballarch-testkit`):
 * - report through [done] at most once per invocation; later reports are ignored;
 * - never retry internally; retries belong to the runtime (see [RetryPolicy]);
 * - report [AdapterResult.NotApplied] only with evidence that the request had
 *   no effect; when in doubt report [AdapterResult.Failed], which the runtime
 *   classifies by the port's effect class;
 * - pass [Invocation.idempotencyKey] to a destination that accepts one, and
 *   never create a key of its own: only the runtime creates keys.
```

## 5. Approvals

An approval is a call on an approval port, such as a service that asks a person and records the answer.

1. The policy decides that a tool call needs approval. It issues an intent for the tool call with `ctx.newIntent()` and calls the approval port. The request names the proposed tool call and carries that intent's id.
2. Declare the approval port Idempotent only if the approval service deduplicates by the idempotency key. Give the approval call an intent of its own, and name it again when you ask after `Unknown`: the person then sees one prompt, not two. Do not name the tool call's intent on the approval call: calls that name one intent must request the same effect ([Core §3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle)).
3. The tool call is made only by the decision that commits `Done(Approved)`. The runtime dispatches a call only after its decision is committed (R5), so the approval is recorded before the tool runs. A denial is a recorded answer: the tool call is never made. A deduplication window counts from the intent's issue ([Core §3.5](../spec/ballarch-core-2.0.md#35-bounds-and-preflight)), so a long wait for approval uses it up.

A call must end by its deadline, and the reference bounds call timeouts at ten minutes by default (`Limits.maxTimeoutMillis`). When a person may take longer, let the approval call only file the request and answer `Done(Requested)`. The approval service then sends the person's answer as a request on the agent Ball's port, authenticated by its inbound adapter, and `decide` accepts it only for a pending approval. The tool call is still made only by the decision that commits the approval.

Who is asked:

| Tool's port | Approval |
|---|---|
| Safe | None for effects. Policy may still ask before private data is read. |
| Idempotent | Once per intent. A repeat with the same intent after `Unknown` does not ask again: the destination applies it at most once. |
| NonIdempotent | Every call. Each call may take effect, so one approval never covers another. |

As of 2026-10-27, the [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/human_in_the_loop/) states that an approval permits a tool call but does not guarantee that it runs exactly once. The same holds here. At most once comes from the effect class and R8, not from the approval.

## 6. Keys come only from the runtime

- **One key per call.** The runtime derives an idempotency key for each call, the same for every attempt. Calls that name one intent share it ([Core §3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle)). The adapter passes `invocation.idempotencyKey` (C5). Neither the model nor the decider makes keys ([R8](../spec/ballarch-core-2.0.md#52-runtime-rules)). If a model writes a key into tool arguments, the adapter replaces it.
- **No transparent retries.** An adapter must not retry (R8, C2). Turn off retries in the SDKs and HTTP clients that adapters use. Put a `RetryPolicy` with an attempt timeout in the binding instead; the composition refuses one on a NonIdempotent port.
- **No blind repeat.** After a NonIdempotent tool call ends `Unknown`, the Ball must not request the same effect again until it has evidence that the first call had no effect ([R15](../spec/ballarch-core-2.0.md#51-author-rules)). That evidence is a Safe status read, made after the destination's delivery horizon, that reports non-application. On a reconciled port the runtime makes this read itself, and a read that reports non-application ends the call `NotDone`, not `Unknown`. After `Unknown` on an Idempotent tool, repeat by naming the earlier call's intent.
- **A window.** Declare `dedupWindowMillis` for a tool server that forgets keys. The runtime then refuses a call whose deadline falls after the window, counted from its intent (scenario S9). Past the window, reconcile instead.

## 7. The final report

The report states what the tools did. The decider computes it from the tool ledger, not from the model's words. Li, [*Where Does Exactly-Once Live?*](https://arxiv.org/abs/2609.29095), arXiv:2609.29095 (accessed 2026-10-27), found that agents usually reported success in the episodes in which they had duplicated an effect.

Sketch: no reference code and no conformance claim.

```kotlin
data class TaskReport(
    val status: Knowledge,            // from the tool ledger by the algebra, never from the model
    val uncertain: List<CallRef<*>>,  // tool calls that ended Unknown and were not settled since
    val narrative: String,            // the model's words, shown as such
)

val status = ledger.entries.fold(Knowledge.NoEffects) { k, e -> k + e.knowledge }
```

- `status` counts each tool call by R14's table ([Core §5.1](../spec/ballarch-core-2.0.md#51-author-rules)). A call the Ball may still make counts as `Maybe`, and so does an open one.
- `uncertain` lists the calls a person may need to check.
- `narrative` explains; it never sets `status`.

Send the report once the run is settled: no tool call open, and none still to be made ([Core §7.4](../spec/ballarch-core-2.0.md#74-composition-closure)). Accept the request with `acceptLater` and reply when the run settles. A progress message before then is a snapshot: label it with its stage and never present it as the result (R14). The trip example replies in this way, from `reference/kotlin/examples/trip/src/main/kotlin/ballarch/examples/trip/TripFlow.kt`:

```kotlin
    /** Ends the trip: nothing can change any more, so its knowledge is final and every waiting Book gets it. */
    private fun settle(state: TripState): Step<TripState, TripReply> {
        val settled = state.copy(stage = Stage.Settled, call = null, attempts = 0, awaiting = emptyList())
        val answer = status(settled)
        return Step(settled, state.awaiting.map { Reply(it, answer) })
    }

    private fun status(state: TripState) = TripReply.Status(state.stage, state.knowledge)
```

[Honest UI](honest-ui.md) maps each knowledge value to a message and a next action.

## 8. Optional check: the lethal trifecta

Willison, [*The lethal trifecta for AI agents*](https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/) (accessed 2026-10-27), warns against one agent that has three things at once: access to private data, exposure to untrusted content, and a way to communicate externally. Ballarch has no built-in check for this. Because every tool is a port bound in one composition root, a test can do it.

Sketch: no reference code and no conformance claim.

```kotlin
enum class Exposure { PrivateData, UntrustedContent, SendsOut }

val exposures = mapOf(
    Inbox to setOf(Exposure.PrivateData, Exposure.UntrustedContent),
    WebFetch to setOf(Exposure.UntrustedContent, Exposure.SendsOut),  // a URL can carry data out
)

@Test fun `no agent holds all three`() {
    for ((agent, ports) in agentPorts) {  // listed by hand next to the composition root
        val held = ports.flatMap { exposures[it].orEmpty() }.toSet()
        assertTrue(held.size < Exposure.entries.size, "$agent holds the lethal trifecta")
    }
}
```

## What this costs, and what it does not do

- Each agent run is a Ball with explicit, serialisable stages. Under the Durable profile, each effectful tool call takes at least three durable writes ([Core §10](../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)).
- Ballarch does not stop prompt injection or decide what a tool may do. It bounds what can be repeated and what the report may claim.
- No profile gives exactly-once execution of external effects (R13).
