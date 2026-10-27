# Contributing

Ballarch publishes an architecture specification, reference implementations at three levels (services, firmware, hardware), and the tools that check them. Useful contributions include:

1. **Independent checks.** Reproduce a specification rule with a small example or a failing conformance test. State the exact version, assumptions, and observed outcome.
2. **Another runtime.** An implementation of [`spec/ballarch-core-2.0.md`](spec/ballarch-core-2.0.md) in another language or on a durable execution engine, checked against the conformance criteria in §9. A runtime that writes trace format v1 ([`tools/TRACE-FORMAT.md`](tools/TRACE-FORMAT.md)) can be judged by [`tools/oracles.py`](tools/oracles.py).
3. **A production durable store** (a database adapter) that meets the store contract of Core §6, with tests of its commit, recovery and exclusive ownership (R16).
4. **Counterexamples.** Schedules, specification ambiguities or prior art that contradict a claim. Report them on GitHub with the evidence.
5. **Other levels.** A Ball on a real microcontroller or FPGA, or in another hardware description language, with checks of the rules at that level.

## Ground rules

- **Evidence first.** Explain the reason for a proposed normative change and, where possible, include a check that fails without it. Describe behavior separately from preferences.
- **Keep claims bounded.** Name the profile, scenario, implementation, and evidence for a claim (Core §9.5). Generated reports belong in ignored build directories; do not treat a passing bounded check as a production guarantee.
- **Style.** Kotlin official code style; KDoc on public API; plain, precise English in documents.

## Checks

Run these before you propose a change. CI runs them too, except the seeded-trace command: there it relies on the Kotlin build, which checks that `tools/oracles.py` agrees with its own trace checker on clean and seeded traces when `python3` is available.

| Check | Command | Needs |
|---|---|---|
| Kotlin build, tests, architecture rules and module boundaries | `./gradlew build checkModuleBoundaries` in `reference/kotlin` | JDK 21 |
| Unit tests of the trace oracles | `python3 -m unittest discover -s tools -p 'test_*.py'` from the repository root | Python 3.8 or later |
| Oracles on the traces the Kotlin build wrote; all must pass | `python3 tools/oracles.py reference/kotlin/ballarch-runtime/build/conformance-traces/*.jsonl` | the Kotlin build |
| Oracles on the seeded traces; each must fail | `python3 tools/oracles.py --expect-violations reference/kotlin/ballarch-runtime/build/conformance-traces/seeded/*.jsonl` | the Kotlin build |
| Firmware reference: tests, purity lint, seeded bugs | `make` in `reference/c` (CI also runs `make sanitize`) | a C11 compiler and binutils |
| Hardware reference: lint, parameter checks, netlist rules, benches, seeded bugs | `make` in `reference/rtl` | Icarus Verilog 12, Verilator 5, Yosys 0.33 or later |
| Alloy model of the call protocol | `bash formal/alloy/fetch-alloy.sh && python3 formal/alloy/run.py` from the repository root | Java, `curl`, `sha1sum` |

[`tools/TRACE-FORMAT.md`](tools/TRACE-FORMAT.md#checking-a-trace) explains the oracle commands, and the [firmware](reference/c/README.md), [hardware](reference/rtl/README.md) and [Alloy](formal/alloy/README.md) READMEs describe what their checks cover.

## Licensing of contributions

Text contributions are accepted under CC BY 4.0 (see [`NOTICE.md`](NOTICE.md)). Code contributions cannot be accepted until the copyright holder chooses a software license.
