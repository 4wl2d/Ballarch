/*
 * Ballarch Core 2 for firmware: the types a decider sees, in C11.
 *
 * A Ball type is a pure step function from (state, input, context) to
 * (next state, calls, reply). This header holds everything a decider needs and
 * nothing of the runtime: the platform, the NVM record and the runtime API are
 * in ballarch_runtime.h, which a decider translation unit must not include
 * (tools/check_purity.sh refuses one that does). The rules are the same as for
 * the Kotlin reference runtime; see spec/ballarch-core-2.0.md.
 *
 * Copyright (c) 2026 Vladislav Tomilov (4wl2d). See NOTICE.md at the
 * repository root: this code has no software license yet.
 */
#ifndef BALLARCH_H
#define BALLARCH_H

#include <stddef.h>
#include <stdint.h>

/* Bounds (rule R11) and sizes of the fixed record; ba_boot refuses a Ball that does not fit. */
#define BA_MAX_STATE 32u              /* bytes of Ball state in the committed record */
#define BA_MAX_CALLS 4u               /* open calls per instance */
#define BA_MAX_OUTPUTS 4u             /* calls per decision */
#define BA_MAX_PORTS 8u               /* ports per Ball type, including the timer port */
#define BA_MAX_KEYS 16u               /* keys one decision may draw with ba_new_key */
#define BA_MAX_TIMEOUT_MS 86400000u   /* call timeout, and timer delay plus margin (Core §3.5) */

/* Use once per Ball state type, e.g. BA_STATE_FITS(th_state); */
#define BA_STATE_FITS(T) _Static_assert(sizeof(T) <= BA_MAX_STATE, #T " exceeds BA_MAX_STATE")

/* Port 0 is the built-in timer (Safe). A timer call's argument is its delay. */
#define BA_TIMER_PORT 0u
#define BA_TIMER_MARGIN_MS 1000u

typedef enum { BA_SAFE = 0, BA_IDEMPOTENT = 1, BA_NON_IDEMPOTENT = 2 } ba_effect;

typedef enum { BA_DONE = 1, BA_NOT_DONE = 2, BA_UNKNOWN = 3 } ba_kind;

/* Reasons carried by NotDone and Unknown outcomes. */
enum {
    BA_R_NEVER_SENT = 1,        /* NotDone: the deadline passed before the call was sent */
    BA_R_REFUSED = 2,           /* NotDone: the destination refused without applying */
    BA_R_NOT_APPLIED = 3,       /* NotDone: the adapter has evidence nothing was applied */
    BA_R_NO_ANSWER = 4,         /* NotDone: a Safe call got no answer */
    BA_R_DEADLINE_AFTER_SEND = 5,
    BA_R_LOST_AFTER_SEND = 6,
    BA_R_RECOVERED_AFTER_CRASH = 7,
    BA_R_RETRIES_EXHAUSTED = 8
};

/* The single terminal outcome of a call. value: the reply (Done) or a reason (otherwise). */
typedef struct {
    ba_kind kind;
    int32_t value;
} ba_outcome;

typedef struct {
    uint8_t port;
    uint32_t key;
    int32_t arg;
    uint32_t timeout_ms; /* at most BA_MAX_TIMEOUT_MS */
} ba_call;

typedef struct {
    uint8_t n;
    ba_call call[BA_MAX_OUTPUTS];
} ba_outputs;

typedef enum { BA_IN_REQUEST = 1, BA_IN_COMPLETION = 2 } ba_input_kind;

typedef struct {
    ba_input_kind kind;
    int32_t request; /* BA_IN_REQUEST */
    int32_t arg;
    uint8_t port; /* BA_IN_COMPLETION */
    uint32_t key;
    ba_outcome outcome;
} ba_input;

/*
 * Trusted values for one decision. Time is a 64-bit millisecond count that
 * never wraps. Keys come from ba_new_key, never from a clock or a random source.
 */
typedef struct {
    uint64_t now_ms;
    uint32_t revision; /* revision the decision commits as */
    uint8_t keys_issued;
} ba_ctx;

/*
 * A fresh call key. Keys of different decisions do not collide until the
 * 32-bit key wraps or the NVM slots are erased, because one decision may draw
 * at most BA_MAX_KEYS keys: the runtime refuses a decision that drew more (a
 * request is refused; a completion quarantines the instance). The count
 * saturates, so an overrun stays visible to the runtime.
 */
static inline uint32_t ba_new_key(ba_ctx *ctx)
{
    uint32_t k = ctx->revision * BA_MAX_KEYS + ctx->keys_issued;
    if (ctx->keys_issued <= BA_MAX_KEYS) ctx->keys_issued++;
    return k;
}

static inline ba_call ba_timer(uint32_t key, uint32_t delay_ms)
{
    ba_call c = {BA_TIMER_PORT, key, (int32_t)delay_ms, delay_ms + BA_TIMER_MARGIN_MS};
    return c;
}

static inline void ba_emit(ba_outputs *out, ba_call c)
{
    if (out->n < BA_MAX_OUTPUTS) out->call[out->n] = c;
    out->n++; /* more than BA_MAX_OUTPUTS is refused by the runtime's preflight */
}

typedef enum { BA_REJECT = 0, BA_ACCEPT = 1 } ba_verdict;

/*
 * A Ball type. step() must be pure (rule R2): it reads only its arguments and
 * writes only *next, *out and *reply. The runtime passes a copy of the
 * committed state, so a decider that casts away const cannot change it. A
 * completion cannot be refused, so for completions step() must return BA_ACCEPT.
 */
typedef struct {
    const char *type;
    uint32_t schema;          /* bump when the meaning of the state's bytes changes (NVM layout check) */
    size_t state_size;        /* at most BA_MAX_STATE */
    uint8_t n_ports;          /* including the timer port; at most BA_MAX_PORTS */
    const ba_effect *effects; /* effects[port] */
    void (*initial)(void *state);
    ba_verdict (*step)(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);
} ba_ball;

#endif
