# Ballarch at every level

Ballarch's outcome rules mention no language, transport or storage. This guide reads them for hardware blocks, firmware, device drivers and driver frameworks. It is informative and expands [Core §11](../spec/ballarch-core-2.0.md#11-interpretation-across-levels-informative); the rules are in Core §2–§7. Mobile and backend structure is in [Structuring an application](structure.md).

Parts marked "Sketch" have no reference code and no conformance claim. External facts are numbered [1], [2], … and listed under [Sources](#sources).

## What does not change between levels

The commit and outcome rules (R4–R8) and the knowledge rule (R14) are the same everywhere. Every call ends `Done`, `NotDone` or `Unknown`, and `NotDone` needs evidence of non-application (R7). Which structural rules apply depends on the elements a level has: a block with no deferred replies has nothing for R9 to check ([Core §9.4](../spec/ballarch-core-2.0.md#94-conformance-at-other-levels)).

At every level, ask five questions:

1. **Owner:** which one Ball changes this state (R1)?
2. **Commit horizon:** when do a decision and its requested effects first exist together (R4, R5)?
3. **Send horizon:** from when may the request have left (R7, R8)?
4. **Effect class:** is a repeat harmless (Safe), deduplicated (Idempotent), or possibly effective each time (NonIdempotent, the default when unsure)?
5. **`Unknown`:** what evidence would settle it, and who collects it?

Idempotent has the narrow meaning of [Core §2](../spec/ballarch-core-2.0.md#2-model): the destination recognises a repeat by a key that every attempt carries. A write that only sets a value is NonIdempotent: a late attempt can overwrite a later request. After such a write ends `Unknown`, write again only once a read of the value, made after the delivery horizon, reports that the write was not applied (R15).

## Synchronous hardware (RTL)

| Concept | In a clocked design |
|---|---|
| Owner | the one `always_ff` process that writes a register; two writers are a multi-driver error |
| Decider | one `always_comb` block with a default for every signal; an inferred latch is hidden state |
| Commit horizon | the clock edge |
| Send horizon | the `valid && ready` handshake on a bus that lets a master withdraw an unaccepted request; on AXI, where VALID may not be withdrawn [6], the assertion of VALID |
| Idempotency key | a command id carried by every attempt; a tag {command id, attempt} is not reused within the response window, across resets too |
| `Unknown` | no response to an accepted transaction by its deadline |
| Profile | Transient: registers do not survive reset |

The [RTL reference](../reference/rtl/README.md) has two blocks. The issuer sends READ (Safe), CONFIG (Idempotent, deduplicated by command id) and FIRE (NonIdempotent). Its parameter check acts as a deduplication window: every retry reaches the device while it still remembers the command id. The composite performs "configure, then fire" and keeps the operation's knowledge (all, any) in a status register, two bits per coordinate, covering operations since the last reset.

Yosys checks single drivers (R1), flip-flops as the only storage (R2), one rising clock edge (R4) and registered outputs (R5). The issuer bench runs 8,764 scenarios at each of three parameter sets, and the composite bench 1,458 (printed by `tb_issuer.sv` and `tb_composite.sv`). Each of 28 seeded bugs must make a bench fail.

## Firmware

The [C reference](../reference/c/README.md) is a Durable runtime for one Ball on a microcontroller:

- Commit horizon: one CRC-checked record in one of two NVM slots. A torn write leaves the previous record valid.
- Send horizon: a write-ahead record that marks the attempt *Sending* before `transmit` runs.
- Each call record keeps the effect class its port had at commit, and the runtime classifies the call by it, also after an update ([Core §6.1](../spec/ballarch-core-2.0.md#61-evolution)).
- The call key is the idempotency key. Keys repeat only when the 32-bit key wraps after enough committed decisions, or after the slots are erased, so an update that erases them needs care ([Core §10](../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)).
- The decider is a pure `step()` in its own translation unit, checked by a lint that is necessary, not sufficient.
- Wear: every commit and every write-ahead record rewrites the whole record in the other slot. An effectful call costs three NVM writes, plus one per retry ([Core §10](../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)), and the reference charges a Safe call the same ([`test_runtime.c`](../reference/c/test/test_runtime.c), tag `wear`). Choose a medium whose endurance covers that rate, or put wear levelling behind `nvm_write`; the reference alternates two slots and does nothing more.

A bounded exhaustive search puts one fault at every power-loss point and NVM write, over the link fates of the first transmissions. At the default bounds it explores 937,229 dosing runs and 982,599 transfer runs with no violation; the tests fail on any other total. The C algebra is checked on the same 69,905 worlds as the Kotlin one. The reference has no Cancel, intents, deduplication windows, attempt timeouts, reconciled ports or reads with a revision.

## Drivers

A driver's Ball is the state of one queue pair or device protocol: its table of outstanding commands with their phases. The interrupt handler is an inbound adapter; it hands completions to the owner and decides nothing. Driver memory is Transient, and a device reset is a failure of the destination, which the owner survives.

| Concept | In a driver |
|---|---|
| Commit horizon | the ledger entry and the descriptor, written while the device cannot see them |
| Send horizon | the first write the device can act on: the ownership flag of a polled ring, the doorbell `writel()`, or a CAN transmission request. The kernel's barrier guide orders a descriptor before its ownership flag with `dma_wmb()`, then rings the doorbell with `writel()` [1]. `writel_relaxed()` is not ordered after DMA-memory writes [2] and must not ring that doorbell [3]: a lint target. |
| Completion | a completion-queue entry, status register or response frame: the adapter's report |
| Interrupt | a hint to read the completion source, not an exactly-once notice. That read is Safe only if it changes nothing. Reading a read-to-clear status or acknowledging the interrupt is NonIdempotent (see Register classes below): do it once, in the adapter, and report the value it read. |
| Deadline | poll the completion source first. Linux's NVMe driver polls the completion queue in its timeout handler before it aborts or resets [4] |
| Reset | capture status first: a reset erases evidence |
| After a reset or AER | every `Sending` command is `Unknown` unless its evidence was captured; a command never published is `NotDone` |
| Retry advice | NVMe's DNR bit means "do not retry" [5], not "nothing was applied"; busy and try-again codes are retry advice too |
| Tags | not reused within the response window, across resets too |

Sketch: no reference code and no conformance claim.

```c
q->pending[tag] = (struct pending){ .phase = PENDING, .req = *req };
d->addr = req->dma; d->len = req->len; d->tag = tag;   /* commit horizon: not yet visible */
q->pending[tag].phase = SENDING;   /* write-ahead: possibly sent from here on */
dma_wmb();                         /* descriptor contents before ownership */
d->status = DEVICE_OWN;            /* a polling device may start here */
writel(q->tail, q->doorbell);      /* never writel_relaxed() here */
```

**Register classes** ([Core §2.6](../spec/ballarch-core-2.0.md#26-declaring-effect-classes)):

| Access | Class |
|---|---|
| a status read with no side effect | Safe |
| a write the device deduplicates by a tag or command id that every attempt carries | Idempotent |
| an absolute configuration write without such a tag | NonIdempotent |
| read-to-clear, write-1-to-clear, FIFO push or pop, interrupt acknowledge by read, doorbell, start bit, flash program or erase, actuator pulse | NonIdempotent |

**What a driver engineer gains:**

1. One rule that splits `-EIO` and `-ETIMEDOUT` into `NotDone` or `Unknown` by phase and class, on every bus.
2. Mechanical retries: only Safe and Idempotent accesses, and only by the runtime.
3. Written rules for recovery: poll first, capture before reset, no tag reuse within the window, `Unknown` after a reset.
4. A cancel that claims no more than the device proves; one CAN controller can confirm a cancel of a frame it still sends [9].
5. A pure step explored on a host with a fault at every power-loss point and NVM write, as in the C reference.
6. A status word, two bits per coordinate, for multi-step work such as erase, program, verify and swap; the RTL composite's bench checks that it never overclaims in any scenario it runs.
7. Quarantine of one instance, not a reboot loop, when a record cannot be used ([Core §6.1](../spec/ballarch-core-2.0.md#61-evolution), item 3).
8. The application team's vocabulary: a device's `Unknown` can reach a user as "checking". No reference composes levels, so nothing checks this path.

## Framework mappings

Sketch: no reference code and no conformance claim.

| Framework | Where the Ball goes | What to add |
|---|---|---|
| QP active objects | an active object already runs each event to completion and exchanges events instead of sharing data [11] | a call ledger with phases; commit before posting an event that leaves |
| RTIC | the Ball's state as a `#[local]` resource, which only its task can access [12] | interrupt handlers post reports to that task |
| Embassy | one task owns the runtime and the peripherals passed to it at spawn [13] | adapters as futures that report back; dropping one is not evidence |
| Zephyr | each port's class as a devicetree property, set by the application, not the board vendor (bindings generate C macros [14]) | an errno-to-outcome table per driver; `i2c_transfer()` reports failure as `-EIO` [15], `Unknown` for an effectful write |
| Tock | an `Ok` from a split-phase call means a callback will follow, and a cancel's `Ok` says whether it cancelled (TRD 3 [16]) | effect classes and `Unknown` |
| Linux | per-queue state under one lock or workqueue; the interrupt handler hands completions to it | no framework is proposed |

## Protocol outcome tables

Each row maps what the sender sees to an outcome for an effectful request; a Safe request without an answer ends `NotDone(NoAnswer)` ([Core §3.2](../spec/ballarch-core-2.0.md#32-call-lifecycle)). Rows without a checked primary source are *unverified*.

**AXI** [6]

| Report | Outcome |
|---|---|
| VALID asserted (§A2.3): it may not be withdrawn before the handshake | send horizon |
| `OKAY` to a non-exclusive write (Table A3.28) | `Done` |
| `SLVERR`: reached an end point, did not complete; the location may be partly updated | `Unknown` |
| `DECERR`: did not reach a point where data can be written; the location may be partly updated | `Unknown` |
| `DEFER`, `UNSUPPORTED` (WriteDeferrable only): the location is not updated | `NotDone` |
| no response by the deadline | `Unknown` |

**AXI firewall** (AMD PG293 [7])

| Report | Outcome |
|---|---|
| an error response that blocks the firewall; its command has already reached the protected side | that response's outcome (`Unknown` for `SLVERR`) |
| `SLVERR` driven by the firewall while it flushes outstanding transactions | `Unknown`: the firewall made it, not the device |

**PCIe**

| Report | Outcome |
|---|---|
| a posted write: no completion | `Unknown` until a later read from the same device returns, which shows the write arrived [2] |
| a fatal uncorrectable error: Linux performs a secondary bus reset [8]; on permanent failure drivers fail pending I/O with `-EIO` [10] | `Unknown` for each command that may have been sent; `NotDone` for one never published |
| a non-posted request without a completion by its Completion Timeout | `Unknown` (*unverified*) |
| a completion with Unsupported Request or Completer Abort status | *unverified* |

**NVMe**

| Report | Outcome |
|---|---|
| a timeout | poll the completion queue [4]; a completion found is the answer; otherwise abort or reset, and `Unknown` for a write |
| an error status with DNR set | do not retry [5]; the outcome by status code is *unverified* |
| a controller reset with commands outstanding | `Unknown` for each outstanding write |
| the completion of an Abort command | *unverified* |

**CAN**

| Report | Outcome |
|---|---|
| transmission request set | send horizon |
| cancellation confirmed | `Unknown`. As of 2026-10-27, Microchip's SAMA7G5 errata describe CAN FD frames of more than 8 data bytes sent in full after a confirmed cancellation [9] |
| transmission complete | the frame reached the bus, not the receiver's application. Errors in the last two bits of end-of-frame can cause duplicates and inconsistent delivery [17], so a NonIdempotent command needs a command id the receiver deduplicates |
| bus-off with a frame pending | *unverified* |

**I2C and SPI** (Linux fault codes [18])

| Report | Outcome |
|---|---|
| `EINVAL`: invalid parameter found before any I/O started | `NotDone` |
| `ESHUTDOWN`: the adapter is suspended | `NotDone` |
| `ENXIO`: the address phase got no ACK | `NotDone` for a one-message write; `Unknown` if earlier messages may have completed |
| `EAGAIN` (arbitration lost), `ETIMEDOUT` (aborted before completion), `EIO`, `EPROTO`, `EBUSY` | `Unknown` |
| an SPI error after the transfer started; SPI has no acknowledgement | `Unknown` (*unverified*) |

## Services and workflows

The [Kotlin reference](../reference/kotlin/README.md) is the service level: a Ball per aggregate key, a Durable store and Flows. The trip and itinerary examples check composite knowledge against ground truth at two depths of composition ([Core Appendix F.3](../spec/ballarch-core-2.0.md#f3-composite-operations-against-ground-truth)). For layout, reads, process death, offline work and HTTP, see [Structuring an application](structure.md); for screens, [Honest UI](honest-ui.md); for other languages, [Bindings](bindings.md).

## Levels without reference code

These rows show how to answer the five questions, not that any product conforms; no check here covers them.

| Level | Owner | Send horizon | `Unknown` when |
|---|---|---|---|
| GPU queues | one hardware queue | the write-pointer update | reset with jobs in flight |
| Network stacks | one connection | the copy into the socket buffer | a reset after it; an acknowledgment covers the peer's stack, not its application |
| RTOS tasks | one task per device protocol | the write that starts a transfer | a watchdog reset after it |
| PLCs | the program that writes an output | the output image or fieldbus frame | a relative move without acknowledgment |
| Databases | the client Ball | `COMMIT` on the socket | the connection drops before the reply |
| Smart contracts | the off-chain sender | the broadcast | the transaction is not yet final |

No reference composes levels. No check covers firmware that drives the RTL block as a Ball behind a port, or a service served by firmware ([Core §10](../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations)).

## Sources

1. [Linux kernel, memory barriers](https://docs.kernel.org/core-api/wrappers/memory-barriers.html), accessed 2026-10-27.
2. [Linux kernel, device I/O](https://docs.kernel.org/driver-api/device-io.html), accessed 2026-10-27.
3. [Will Deacon, DMA vs MMIO ordering fix, 2018](https://lkml.iu.edu/1803.3/01874.html), accessed 2026-10-27.
4. [Linux `drivers/nvme/host/pci.c`, `nvme_timeout`](https://github.com/torvalds/linux/blob/master/drivers/nvme/host/pci.c), as of 2026-10-27.
5. [Mike Snitzer, DNR patch, 2021](https://lists.infradead.org/pipermail/linux-nvme/2021-April/024404.html), accessed 2026-10-27.
6. [Arm IHI 0022, AMBA AXI Protocol Specification, Issue L](https://documentation-service.arm.com/static/68b03beb01ae952d9559f9eb), accessed 2026-10-27.
7. AMD PG293: [Error Response Faults](https://docs.amd.com/r/en-US/pg293-axi-firewall/Error-Response-Faults), [SLVERR and DECERR Summary](https://docs.amd.com/r/en-US/pg293-axi-firewall/SLVERR-and-DECERR-Summary), accessed 2026-10-27.
8. [Linux kernel, PCIe AER](https://docs.kernel.org/PCI/pcieaer-howto.html), accessed 2026-10-27.
9. [Microchip, SAMA7G5 errata 21.9](https://onlinedocs.microchip.com/oxy/GUID-CFE060D6-6076-46C0-B8FD-60B6DA382A42-en-US-8/GUID-C02B5019-9536-43EE-BCA4-AA8D209A1A80.html), accessed 2026-10-27.
10. [Linux kernel, PCI error recovery](https://docs.kernel.org/PCI/pci-error-recovery.html), accessed 2026-10-27.
11. [Quantum Leaps, QP frameworks](https://www.state-machine.com/qp), accessed 2026-10-27.
12. [RTIC book, resources](https://rtic.rs/2/book/en/by-example/resources.html), accessed 2026-10-27.
13. [Embassy book](https://embassy.dev/book/), accessed 2026-10-27.
14. [Zephyr, devicetree bindings syntax](https://docs.zephyrproject.org/latest/build/dts/bindings-syntax.html), accessed 2026-10-27.
15. [Zephyr, I2C API](https://docs.zephyrproject.org/latest/doxygen/html/group__i2c__interface.html), accessed 2026-10-27.
16. [Tock, TRD 3](https://book.tockos.org/trd/trd3-hil-design), accessed 2026-10-27.
17. [Rufino et al., "Fault-Tolerant Broadcasts in CAN", FTCS-28, 1998](https://www.dpss.inesc-id.pt/~ler/pubs/canamp.html), accessed 2026-10-27.
18. [Linux kernel, I2C fault codes](https://docs.kernel.org/i2c/fault-codes.html), accessed 2026-10-27.
