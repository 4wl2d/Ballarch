# Ballarch in Kotlin (JVM)

The service-level reference implementation of [Ballarch Core 2.0](../../spec/ballarch-core-2.0.md), for both profiles, Transient and Durable. The specification is normative; this code is one way to meet it. The [tutorial](../../docs/tutorial.md) walks through one example.

## Modules

| Module | Contents |
|---|---|
| `ballarch-kernel` | `Ball`, ports and effect classes, outcomes, `Context.call` and `CallRef`, `Intent`, `Timer`, the knowledge algebra. Nothing beyond the Kotlin standard library and `java.io.Serializable`. |
| `ballarch-runtime` | `Runtime`, `Composition`, `RetryPolicy`, `Reconciliation`, `ThreadScheduler`, `InlineScheduler`, `MemoryStore`, `FileStore`, tracing. |
| `ballarch-testkit` | `Harness`, `SimPort`, `explore`, `Oracles`, `TraceJson`, `AdapterConformance`. |
| `ballarch-archrules` | ArchUnit rules (`BallarchRules`): decider purity, immutable values, no global mutable state, public feature APIs. |
| `examples/` | `order-draft` (no effects), `tutorial` (a NonIdempotent mailer), `place-order` (intents, retries, a window, reconciliation), `catalog` (a Safe search, latest wins), `shop` (a checkout Flow over three Balls, an HTTP adapter, module boundaries), `trip` (compensations and knowledge), `itinerary` (two trips composed). |

## Build

Needs JDK 21. From this directory:

```sh
./gradlew build checkModuleBoundaries
```

`build` compiles every module and runs every test. `checkModuleBoundaries` fails if a shop module depends on another feature's implementation module. One module: `./gradlew :examples:place-order:test`.

Most explorations print their run counts, which Gradle keeps in each module's `build/test-results/test/`. The tests of `ballarch-runtime` write conformance traces ([format](../../tools/TRACE-FORMAT.md)) to `ballarch-runtime/build/conformance-traces/`. From the repository root, after the build:

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/oracles.py reference/kotlin/ballarch-runtime/build/conformance-traces/*.jsonl
python3 tools/oracles.py --expect-violations reference/kotlin/ballarch-runtime/build/conformance-traces/seeded/*.jsonl
```

Each command exits with 0 when the traces conform.

## Checks

| Tests | What they check |
|---|---|
| `KnowledgeTest`, `CallRefTest` | the knowledge algebra, exhaustively; typed completions |
| `RuntimeTest`, `RuntimeRulesTest` | classification, keys and intents, windows, attempt timeouts, reads, the timer across a restart (S7), boundary cases B1–B4 |
| `ExplorationTest` | scenarios S1–S6, S8, S9 and S12 under every fate and crash point, each run checked by `Oracles` against ground truth, with negative controls |
| `ReconciliationTest` | reconciled ports (S10), with a destination that breaks its horizon as the control |
| `OwnershipTest`, `EvolutionTest` | exclusive ownership on both stores (S11); state versions, recorded effect classes, refusals to start (B5) |
| `FileStoreTest`, `InlineSchedulerTest`, `TraceConformanceTest` | recovery from disk; inline steps; the trace writer, and agreement of both trace checkers |
| `BallarchRulesTest` | each rule catches its seeded violation; the order-draft, tutorial, place-order, catalog, trip, itinerary and shop examples satisfy every rule |
| `HttpAdapterConformanceTest` | adapter checks C1–C5 on the shop's HTTP adapter, which passes, and on five broken adapters, which fail |
| example tests | deciders as plain functions, and runs under faults against ground truth |

The purity rule treats every class in a package that contains a Ball as decider code, so the examples keep their composition roots and bindings in `app` packages.

[Core Appendix F](../../spec/ballarch-core-2.0.md#appendix-f-checks-and-evidence-in-the-reference-implementations-informative) maps each rule and scenario to its test, and says which figures are asserted and which are only printed. Decider termination, ownership boundaries, declared effect classes and adapter honesty beyond C1–C5 are left to review ([Core §10](../../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)).
