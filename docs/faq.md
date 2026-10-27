# FAQ

## Positioning

**Is it better than Clean Architecture?**
Not in general, and nothing in this repository measures it. The two answer different questions. Clean Architecture says where source dependencies may point. Ballarch says what code may claim after it calls something that can fail or take effect twice. Careful Clean code can handle a lost answer, a double tap, process death and an expired idempotency key correctly. It does so in each feature, or in a shared helper the team writes, with its own key store, in-flight guard, recovery and reconciliation. Ballarch writes those once, in the runtime, from the effect class each port declares, and makes `Unknown` a case the compiler asks you to handle. It costs a runtime, explicit stages and, under the Durable profile, serialisable state and a store. If you have a few effectful writes, owned by people who know their failure modes, careful Clean may be enough. [Compared with Clean](compared-with-clean.md) works through one feature in both styles.

**Is this new?**
Mostly not, and [Core §12](../spec/ballarch-core-2.0.md#12-lineage-and-what-is-new-informative) cites the prior art for each element. As far as the author could establish, four things are new:

1. **Composite knowledge**, the lead claim: a new application of a classical structure. A Ball computes an (all, any) value over Kleene's three-valued logic for an operation of several calls, with the kernel's `Knowledge` algebra. It does not depend on order, counts only absorbing compensations as undoing, is closed under nesting, and is returned as the settled reply.
2. **One outcome rule for every port**: a new combination; the rule itself exists. The class is declared on the trusted side, retry policies are refused when the composition is built, the phase is persisted, and a typed completion goes to a pure decider.
3. **Keys, windows and reconciliation in the runtime**: a modest combination.
4. **The same outcome and knowledge rules, checked at three levels**: a narrow combination. The structural rules differ per level, and no reference composes levels.

Single owners, pure deciders, commit before dispatch, three outcomes and idempotency keys are established practice.

**Isn't the algebra just intervals?**
Yes. The knowledge values are intervals, and soundness and exactness follow from known properties of Kleene's connectives. §12 claims the application, not the structure: each call counts by its outcome and by whether it may still be made, only absorbing compensations count as undoing, and a settled composite returns the value as its reply, so composites nest like single calls ([Core §4.2](../spec/ballarch-core-2.0.md#42-knowledge-of-composite-operations), [§7.4](../spec/ballarch-core-2.0.md#74-composition-closure)).

**Isn't this TCA, Elm or actors?**
The decider is. A pure function from state and input to new state and outputs is Elm's `update`, a TCA reducer or a Mobius update. A single owner is an actor or an Orleans grain. §12 lists them as prior art. What they leave to each application is what an effect's outcome may claim. Elm's `Http.Error`, for example, has `Timeout` and `NetworkError`, and no way to say whether the request was sent ([elm/http](https://github.com/elm/http/blob/master/src/Http.elm), accessed 2026-10-27). Ballarch adds the outcome rule, commit before dispatch, and the knowledge algebra to the owner and the decider.

**Why not Temporal or Restate?**
If you already run one, you may not need Ballarch's runtime. Those systems have more engineering behind them. Two differences remain, as of 2026-10-27:

- **No replay.** Temporal requires Workflow code to be deterministic "to support replay", and recommends versioning when that code changes ([Temporal](https://docs.temporal.io/workflow-definition), accessed 2026-10-27). Restate recovers a handler by replaying its journal ([Restate](https://docs.restate.dev/concepts/durable_execution), accessed 2026-10-27). A Ballarch Durable runtime restarts from committed records and never re-runs decider code to regenerate outputs ([R4](../spec/ballarch-core-2.0.md#52-runtime-rules)). A changed decider never has to reproduce an old decision. A changed state shape needs a version and a migration instead ([Core §6.1](../spec/ballarch-core-2.0.md#61-evolution)).
- **Outcomes by class.** Temporal retries Activities with unlimited attempts by default ([retry policies](https://docs.temporal.io/encyclopedia/retry-policies), accessed 2026-10-27) and recommends idempotent Activities, because they "may be executed more than once" ([Activities](https://docs.temporal.io/activity-definition), accessed 2026-10-27). What a timed-out non-idempotent Activity did is left to workflow code. In Ballarch the port declares its class, the composition refuses a retry policy on a NonIdempotent port, and a call that may have been sent without an answer completes `Unknown`, a typed value that composes.

Inside such an engine you can apply the rule by hand: declare each activity's class, allow one attempt for a non-idempotent one, and treat a timeout after it may have started as unknown.

## Semantics

**Why three outcomes?**
Because a lossy channel forces the third. When messages can be lost, a caller that must finish by a deadline can be left without an answer about a request that took effect. This is the coordinated-attack problem (Gray 1978; Halpern and Moses 1990; see [Core Appendix B](../spec/ballarch-core-2.0.md#appendix-b-references)). Ballarch gives the third outcome a type and rules for evidence ([Core §2.2](../spec/ballarch-core-2.0.md#22-outcomes), R7).

**Why is `Unknown` not an exception?**
Because it is not exceptional. After a timeout, a crash or an error after the request left, not knowing is the correct state. An exception is usually caught by a handler that maps it to "failed". As a case of `Outcome`, `Unknown` must be handled where the completion is read, and the knowledge algebra can count it.

**Does it give exactly-once effects?**
No profile does (R13). A NonIdempotent call is sent at most once. Every attempt of a call carries one idempotency key, and calls that name one intent share it, so a destination that deduplicates applies the effect at most once within its window. A preflight check refuses a call that would end beyond a declared window. When a call ends `Unknown`, the application is told so, and may repeat it by intent or reconcile it.

**What happens when the process crashes?**
It depends on the profile ([Core §6](../spec/ballarch-core-2.0.md#6-profiles)).

- **Transient:** state, open calls and open requests are lost together, and no half-applied decision remains. Idempotency keys do not repeat after the restart.
- **Durable:** the runtime restarts from committed records. Calls never sent are sent. Idempotent and Ball-port calls that may have been sent are sent again, an Idempotent one with its original key. NonIdempotent calls that may have been sent end `Unknown(RecoveredAfterCrash)`, or are reconciled on a reconciled port. Timers are re-armed to their due time, and notices are redelivered.

The reference runtime has two stores. `MemoryStore` simulates restarts in tests. `FileStore` writes a temporary file, forces it to the device and renames it over the previous one; a test kills a writing process repeatedly and checks that every record reads back whole. It locks its directory on one host, has no replication and has not been tested against power loss.

**What happens when I deploy while calls are open?**
Under the Durable profile, new code starts from records old code committed ([Core §6.1](../spec/ballarch-core-2.0.md#61-evolution)). Each record carries its state version, converted by a pure `migrate`. Each call keeps the effect class it was committed with. Before writing anything, the runtime checks the store, before it takes its incarnation and again once it holds exclusive ownership, and refuses to start if a record names a Ball type, port or subscription the composition no longer provides, so drain those first. A refusal after it has fenced a predecessor leaves that predecessor stopped. Only one runtime may commit to an instance ([R16](../spec/ballarch-core-2.0.md#52-runtime-rules)). The reference memory store fences older runtimes, and the file store locks its directory on one host. Not checked: a new decider that no longer handles completions of a bound port; such a completion quarantines the instance.

**Can a Ball call another Ball and wait for the answer?**
It can call. It cannot wait inside a decision: the answer arrives later as a completion. This rules out deadlocks and re-entrant decisions ([Core §3.4](../spec/ballarch-core-2.0.md#34-ball-to-ball-calls)).

## Adoption

**Can I adopt it gradually?**
Yes, one feature at a time. Keep the read path (repositories, caches, paging) as plain code, and move effectful writes into Balls. The old repository call becomes an adapter. Code that calls an effectful port outside a Ball is outside Core's guarantees, so a mixed application makes no claim for the parts not yet moved ([Core §1](../spec/ballarch-core-2.0.md#1-scope)). See [compared with Clean](compared-with-clean.md).

**Do I need the Durable profile on mobile?**
Only if the Ball's effects outlive the process, and an order on a server does. Under the Transient profile a process death erases the Ball's calls, the restarted Ball can claim only `Unknown` about them, and a repeat without an operation key can apply the effect twice ([Core §10](../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)). Either run the Ball under the Durable profile on a local database, or put an operation key, such as an order id saved with the draft, in the request so the server absorbs a repeat ([Core §7.2](../spec/ballarch-core-2.0.md#72-workflows)). A UI Ball that calls only Safe ports, such as a search, needs neither. The reference has no Room or SQLite store; [bindings](bindings.md) describes one.

**What does a runtime cost to write?**
A Transient runtime needs serial steps per instance; decide, preflight, atomic commit, then dispatch; a call table with phase, recorded class, attempts, intent and key; one classification function for [Core §3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle); deadlines, and attempt timeouts if it offers them; bounds; and reads. A Durable runtime adds the store contract, write-ahead records, recovery and exclusive ownership. [Bindings](bindings.md) gives the checklist, and [Core §9.1](../spec/ballarch-core-2.0.md#91-a-conforming-runtime) the evidence a conforming runtime needs.

**Can I use Swift, Rust or TypeScript?**
Yes, but there is no reference code in those languages. [Bindings](bindings.md) sketches each one, without a conformance claim. A runtime you write conforms only with the evidence of [Core §9.1](../spec/ballarch-core-2.0.md#91-a-conforming-runtime).

**Can a runtime in another language be checked against the rules?**
Yes, for the rules a trace can show. [Core §9.6](../spec/ballarch-core-2.0.md#96-conformance-trace) defines a conformance trace in JSON Lines ([format](../tools/TRACE-FORMAT.md)): the runtime's events, and the ground truth a simulated environment records for each call. [`tools/oracles.py`](../tools/oracles.py), with Python's standard library only, checks the trace properties T3 and T5–T9. They do not cover atomic commit, bounds, profile claims or exclusive ownership. The Kotlin build writes clean traces that must pass and seeded ones that must fail.

**Is it a framework?**
The kernel is types and the knowledge algebra, with no dependency beyond the Kotlin standard library and `java.io.Serializable`. The reference runtime is one implementation of the [specification](../spec/ballarch-core-2.0.md); others may implement it, and conformance is defined in §9.

**Does it really apply to hardware?**
The rules describe owned state, atomic commit and outcomes of requests, and synchronous circuits have all three. [`reference/rtl/`](../reference/rtl/) has two SystemVerilog blocks: a command issuer, and a composite that keeps the knowledge of its two-step operation in a status register. [`reference/c/`](../reference/c/) does the same for firmware with power loss. Each is checked at its own level; none is composed with a service. [Levels](levels.md) also reads the rules for device drivers, without reference code.

## The project

**Why "Ballarch"?**
"Ball" is the unit of ownership; "arch" stands for architecture. The project was called Pokeball up to Core 1.5 and was renamed Ballarch. Ballarch is not affiliated with Nintendo.

**Where did the Interaction, Nucleus and Resource roles of 1.5 go?**
They became the decider and the adapters. See the [migration guide](migration-from-1.5.md).

**Is there a Russian version?**
Yes. [`docs/ru/`](ru/README.md) mirrors the README and every guide. English is canonical, and each Russian page links to its source.

**Can I use the code?**
The text is CC BY 4.0. The executable code has no software license yet; see [NOTICE.md](../NOTICE.md).
