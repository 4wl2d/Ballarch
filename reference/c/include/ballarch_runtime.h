/*
 * The Ballarch Core 2 firmware runtime: board services, the committed NVM
 * record and the runtime API.
 *
 * One runtime executes one Ball instance (a firmware task that owns a piece of
 * device state). It commits the new state and the call ledger to non-volatile
 * memory in one atomic record (two slots, sequence number, CRC), transmits
 * only after that commit, and gives every call exactly one outcome chosen from
 * its phase and the effect class its port had when the call was committed.
 * Deciders include ballarch.h only.
 *
 * Copyright (c) 2026 Vladislav Tomilov (4wl2d). See NOTICE.md at the
 * repository root: this code has no software license yet.
 */
#ifndef BALLARCH_RUNTIME_H
#define BALLARCH_RUNTIME_H

#include "ballarch.h"

/* What an adapter reports for one attempt (exactly once, never after retrying by itself). */
typedef enum {
    BA_REPORT_OK = 1,          /* the destination processed the request; value is its answer */
    BA_REPORT_REFUSED = 2,     /* the destination refused without applying anything */
    BA_REPORT_NOT_APPLIED = 3, /* evidence that nothing was applied (e.g. NACK before the command was latched) */
    BA_REPORT_FAILED = 4       /* no usable answer after the request may have left */
} ba_report_kind;

/* Trace events for tests and diagnostics. */
enum { BA_T_CALL_COMMITTED = 1, BA_T_SENT = 2, BA_T_COMPLETED = 3, BA_T_LATE_REPORT = 4, BA_T_FAULT = 5 };

/* Services of the board (or of a simulator). */
typedef struct {
    void *user;
    /*
     * Non-volatile memory with two record slots. A write may be cut short by
     * power loss. nvm_write returns 0 only once the record is stored and
     * verified (for example read back); otherwise nonzero. The runtime
     * transmits only after the write-ahead record's nvm_write returned 0 (R8),
     * and quarantines the instance when a write fails.
     */
    int (*nvm_write)(void *user, int slot, const void *data, size_t n);
    int (*nvm_read)(void *user, int slot, void *data, size_t n); /* 0 if the slot was never written */
    /* One attempt of a call on an adapter port (port >= 1). The adapter reports later with ba_report. */
    void (*transmit)(void *user, uint8_t port, uint32_t key, int32_t arg, uint8_t attempt);
    /*
     * Milliseconds from a fixed epoch, 64 bits: must never wrap and must keep
     * running across power loss. A board with a 32-bit tick counter extends it
     * to 64 bits here.
     */
    uint64_t (*now)(void *user);
    void (*trace)(void *user, int event, uint32_t key, const ba_outcome *outcome); /* may be NULL */
} ba_platform;

typedef enum { BA_PENDING = 1, BA_SENDING = 2 } ba_phase;

/* One open call. Laid out without padding, so every byte of the record is defined. */
typedef struct {
    uint64_t deadline;
    uint32_t key;
    int32_t arg;
    uint8_t used, port, phase, attempts;
    /*
     * Low byte: the port's effect class when the call was committed, plus 1. The
     * runtime classifies the call by it for its whole life, also after a restart
     * into a build whose port table declares the port otherwise; the attempt
     * budget is the current build's. 0, in a record written before the class
     * was recorded, classifies the call by the current port table. The other
     * bytes are 0.
     */
    uint32_t reserved;
} ba_call_rec;

/*
 * Everything that survives a power loss. Written as a whole, to one slot, with
 * a CRC. The first 16 bytes are a header that every record layout keeps at the
 * same offsets: magic, layout, sequence number and a CRC of those three. So a
 * record written by another firmware build is recognised even when its size
 * differs, and a torn write that programmed only part of a header (say, the
 * magic word over erased flash) is not taken for one.
 */
typedef struct {
    uint32_t magic;
    uint32_t layout;   /* ba_layout() of the Ball and record that wrote it */
    uint32_t seq;
    uint32_t head_crc; /* ba_crc32 of magic, layout and seq */
    uint32_t revision;
    uint32_t reserved; /* 0: keeps the calls 8-byte aligned without padding */
    ba_call_rec calls[BA_MAX_CALLS];
    uint8_t state[BA_MAX_STATE];
    uint32_t crc;      /* ba_crc32 of everything before it */
} ba_record;

typedef struct {
    uint32_t key;
    uint8_t port;
    ba_outcome outcome;
} ba_resolution;

/* The runtime. Everything here except `rec` is volatile and lost at a power loss. */
typedef struct {
    const ba_ball *ball;
    const ba_platform *pf;
    const uint8_t *max_attempts; /* per port; only Safe and Idempotent ports retry */
    uint32_t layout;             /* ba_layout(ball) */
    ba_record rec;
    ba_resolution queue[BA_MAX_CALLS]; /* resolved, not yet committed by the Ball */
    uint8_t queued;
    uint8_t current_attempt[BA_MAX_CALLS];
    int quarantined;
} ba_runtime;

/*
 * Recover from NVM and apply the restart rules of Core §3.7. Starts fresh if
 * nothing was ever committed. Quarantines the instance if the Ball does not fit
 * the fixed record (R11), or if a record was committed but cannot be used: both
 * slots corrupt, or a record written by a build with another record layout or
 * Ball type (its header CRC holds) that is newer than every record of this build.
 */
void ba_boot(ba_runtime *rt, const ba_ball *ball, const ba_platform *pf, const uint8_t *max_attempts);

/* Decide an external request. Returns 1 if accepted and committed, 0 if rejected, -1 if refused by a bound or fault. */
int ba_request(ba_runtime *rt, int32_t request, int32_t arg, int32_t *reply);

/* An adapter's report for an attempt. Reports for closed calls or old attempts are dropped. */
void ba_report(ba_runtime *rt, uint32_t key, uint8_t attempt, ba_report_kind kind, int32_t value);

/* Fire due timers and deadlines, then let the Ball commit pending completions. */
void ba_tick(ba_runtime *rt);

/* Earliest time at which ba_tick has work, or UINT64_MAX. */
uint64_t ba_next_wakeup(const ba_runtime *rt);

/* Read-only view of the committed state (rule R12). */
const void *ba_state(const ba_runtime *rt);

/* The layout fingerprint a record written for this Ball carries. */
uint32_t ba_layout(const ba_ball *ball);

uint32_t ba_crc32(const void *data, size_t n);

#endif
