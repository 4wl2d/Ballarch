/*
 * Deterministic tests of runtime paths that the example explorations do not
 * reach, each driven through the public API with a probe Ball:
 *   preflight-n  decisions that break preflight check n are refused (-1), nothing is committed or sent
 *   keys         a decision that draws more than BA_MAX_KEYS keys is refused
 *   quarantine   a refused completion quarantines the instance
 *   retry        an Idempotent call is retried with a write-ahead record per attempt; a NACK of a
 *                retry is not evidence about earlier attempts (Unknown), stale reports are ignored
 *   nvm-error    a failed NVM write stops the commit or the send; after a restart a Pending call is
 *                sent once, or ends NotDone(NeverSent) past its deadline
 *   ball-check   a Ball that does not fit the fixed record is quarantined at boot
 *   corrupt      both slots committed and corrupt: quarantine; one torn first write: a fresh start; a
 *                write torn after the magic word (word-programmed flash) is not taken for another
 *                build's record, on the first commit or next to a committed record
 *   layout       a record written for another Ball type or schema: quarantine
 *   state-copy   a decider that casts away const cannot change the committed state
 *   effect-class a call is classified by the effect class recorded when it was committed, not by the
 *                port table of the build that recovers it: in recovery, retries, deadline outcomes and NACKs
 *   wear         one call of each class answered OK costs three NVM writes, and a retry one more
 *   time         a timer armed just before 2^32 ms fires at its due time, not before
 * A failure prints "counterexample in run 0 [tag]: ..." and exits 1; each seeded
 * runtime bug listed for this test in the Makefile must produce its tag.
 */
#include "ballarch_runtime.h"

#include <stdio.h>
#include <string.h>

static int failures;

static void fail(const char *tag, const char *what)
{
    if (failures++ == 0) printf("counterexample in run 0 [%s]: %s\n", tag, what);
    else printf("  also [%s]: %s\n", tag, what);
}

#define EXPECT(tag, cond, what) \
    do {                        \
        if (!(cond)) fail(tag, what); \
    } while (0)

/* ---------------------------------------------------------------- the board */

static struct {
    ba_record slot[2];
    int written[2];
    int writes, fail_write; /* fail_write: the number of the write that fails (1-based), 0 for none */
    int tear_write;         /* the number of the write that power loss cuts after the magic word, 0 for none */
    uint64_t clock;
    int transmissions, write_ahead_missing;
    uint8_t last_port, last_attempt;
    uint32_t last_key;
    int faults, completions;
    ba_outcome last_outcome;
} B;

static int nvm_write(void *u, int slot, const void *data, size_t n)
{
    (void)u;
    ++B.writes;
    if (B.writes == B.fail_write) return -1;
    if (B.writes == B.tear_write) { /* the slot was erased; only its first word was programmed */
        memset(&B.slot[slot], 0xFF, sizeof B.slot[slot]);
        memcpy(&B.slot[slot], data, sizeof(uint32_t));
        B.written[slot] = 1;
        return -1; /* the power is gone; the test reboots the controller */
    }
    memcpy(&B.slot[slot], data, n);
    B.written[slot] = 1;
    return 0;
}

static int nvm_read(void *u, int slot, void *data, size_t n)
{
    (void)u;
    if (!B.written[slot]) return 0;
    memcpy(data, &B.slot[slot], n);
    return 1;
}

static const ba_record *durable(void)
{
    const ba_record *best = NULL;
    for (int s = 0; s < 2; s++) {
        const ba_record *r = &B.slot[s];
        if (!B.written[s] || r->crc != ba_crc32(r, offsetof(ba_record, crc))) continue;
        if (best == NULL || r->seq > best->seq) best = r;
    }
    return best;
}

static void transmit(void *u, uint8_t port, uint32_t key, int32_t arg, uint8_t attempt)
{
    (void)u;
    (void)arg;
    const ba_record *r = durable();
    int recorded = 0;
    for (unsigned i = 0; r && i < BA_MAX_CALLS; i++)
        if (r->calls[i].used && r->calls[i].key == key && r->calls[i].phase == BA_SENDING && r->calls[i].attempts == attempt) recorded = 1;
    if (!recorded) B.write_ahead_missing++;
    B.transmissions++;
    B.last_port = port;
    B.last_key = key;
    B.last_attempt = attempt;
}

static uint64_t now(void *u)
{
    (void)u;
    return B.clock;
}

static void trace(void *u, int event, uint32_t key, const ba_outcome *o)
{
    (void)u;
    (void)key;
    if (event == BA_T_FAULT) B.faults++;
    if (event == BA_T_COMPLETED) {
        B.completions++;
        B.last_outcome = *o;
    }
}

static const ba_platform PF = {NULL, nvm_write, nvm_read, transmit, now, trace};

static void board_reset(void)
{
    memset(&B, 0, sizeof B);
    memset(B.slot, 0xFF, sizeof B.slot); /* erased flash */
    B.clock = 1000;
}

/* ---------------------------------------------------------------- a probe Ball */

enum { P_ACT = 1, P_SET = 2, P_GET = 3, P_PORTS = 4 }; /* NonIdempotent, Idempotent, Safe */
enum { PB_CALL = 1, PB_TIMER = 2, PB_BAD = 3, PB_REJECT_COMPLETIONS = 4, PB_WRITE_STATE = 5, PB_NOTE = 6 };

typedef struct {
    uint8_t reject_completions, completions;
    uint32_t last_key;
} pb_state;

/* Sized for every possible port, so that a mutant without the port check reads no memory out of bounds. */
static const ba_effect PB_EFFECTS[BA_MAX_PORTS + 1] = {BA_SAFE, BA_NON_IDEMPOTENT, BA_IDEMPOTENT, BA_SAFE};

static void pb_initial(void *state) { memset(state, 0, sizeof(pb_state)); }

static void emit(ba_outputs *out, uint8_t port, uint32_t key, uint32_t timeout)
{
    ba_call c = {port, key, 7, timeout};
    ba_emit(out, c);
}

/* A decision that breaks preflight check `which` (numbered as in ballarch_runtime.c), or draws 17 keys (9). */
static void bad(const pb_state *s, ba_ctx *ctx, ba_outputs *out, int which)
{
    switch (which) {
    case 1:
        for (int i = 0; i < 5; i++) emit(out, P_GET, ba_new_key(ctx), 300);
        break;
    case 2: /* one call is open already: four more exceed BA_MAX_CALLS */
        for (int i = 0; i < 4; i++) emit(out, P_GET, ba_new_key(ctx), 300);
        break;
    case 3: emit(out, P_PORTS, ba_new_key(ctx), 300); break;
    case 4: emit(out, P_GET, ba_new_key(ctx), 0); break;
    case 5: emit(out, P_GET, ba_new_key(ctx), BA_MAX_TIMEOUT_MS + 1u); break;
    case 6: {
        ba_call t = ba_timer(ba_new_key(ctx), 100);
        t.timeout_ms = 50; /* not made with ba_timer's margin */
        ba_emit(out, t);
        break;
    }
    case 7: emit(out, P_GET, s->last_key, 300); break; /* the key of the open call */
    case 8: {
        uint32_t k = ba_new_key(ctx);
        emit(out, P_GET, k, 300);
        emit(out, P_GET, k, 300);
        break;
    }
    case 9: {
        uint32_t k = 0;
        for (int i = 0; i < 17; i++) k = ba_new_key(ctx);
        emit(out, P_GET, k, 300); /* the 17th key is the first key of the next revision */
        break;
    }
    }
}

static ba_verdict pb_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    const pb_state *s = state;
    pb_state *n = next;
    *n = *s;
    if (in->kind == BA_IN_COMPLETION) {
        if (s->reject_completions) return BA_REJECT;
        n->completions++;
        return BA_ACCEPT;
    }
    switch (in->request) {
    case PB_CALL:
        n->last_key = ba_new_key(ctx);
        emit(out, (uint8_t)in->arg, n->last_key, 300);
        *reply = (int32_t)n->last_key;
        return BA_ACCEPT;
    case PB_TIMER:
        n->last_key = ba_new_key(ctx);
        ba_emit(out, ba_timer(n->last_key, (uint32_t)in->arg));
        return BA_ACCEPT;
    case PB_BAD:
        bad(s, ctx, out, in->arg);
        return BA_ACCEPT;
    case PB_REJECT_COMPLETIONS:
        n->reject_completions = 1;
        n->last_key = ba_new_key(ctx);
        emit(out, P_GET, n->last_key, 300);
        return BA_ACCEPT;
    case PB_WRITE_STATE:
        ((uint8_t *)(uintptr_t)state)[0] = 0xEE; /* casts away const: must not reach the committed record */
        return BA_REJECT;
    case PB_NOTE: /* commits a mark in the state, and no call */
        n->completions = (uint8_t)in->arg;
        return BA_ACCEPT;
    default:
        return BA_REJECT;
    }
}

static const ba_ball PROBE = {"probe", 1u, sizeof(pb_state), P_PORTS, PB_EFFECTS, pb_initial, pb_step};
static const ba_ball PROBE_V2 = {"probe", 2u, sizeof(pb_state), P_PORTS, PB_EFFECTS, pb_initial, pb_step};
static const ba_ball OVERSIZE = {"probe", 1u, BA_MAX_STATE + 8u, P_PORTS, PB_EFFECTS, pb_initial, pb_step};
static const ba_ball TOO_MANY_PORTS = {"probe", 1u, sizeof(pb_state), BA_MAX_PORTS + 1u, PB_EFFECTS, pb_initial, pb_step};

static const uint8_t BUDGET[P_PORTS] = {0, 0, 2, 2};
static const uint8_t BUDGET3[P_PORTS] = {0, 0, 3, 2};

static ba_runtime rt;

/* ---------------------------------------------------------------- tests */

static void preflight(void)
{
    char what[120];
    for (int which = 1; which <= 9; which++) {
        board_reset();
        ba_boot(&rt, &PROBE, &PF, BUDGET);
        int32_t reply = 0;
        ba_request(&rt, PB_CALL, P_GET, &reply); /* one open call, for checks 2 and 7 */
        uint32_t seq = durable()->seq, revision = rt.rec.revision;
        int sent = B.transmissions;
        int r = ba_request(&rt, PB_BAD, which, &reply);
        char tag[16];
        snprintf(tag, sizeof tag, which == 9 ? "keys" : "preflight-%d", which);
        snprintf(what, sizeof what, "a decision that breaks check %d returned %d, committed %u records and sent %d calls", which, r,
                 durable()->seq - seq, B.transmissions - sent);
        EXPECT(tag, r == -1 && durable()->seq == seq && rt.rec.revision == revision && B.transmissions == sent, what);
    }
    /* A timer of 2^31 ms or more has a negative argument and a timeout above the bound. */
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    EXPECT("preflight-5", ba_request(&rt, PB_TIMER, (int32_t)0x80000000u, NULL) == -1, "a timer of 2^31 ms was accepted");
}

static void quarantine(void)
{
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    ba_request(&rt, PB_REJECT_COMPLETIONS, 0, NULL);
    ba_report(&rt, B.last_key, 1, BA_REPORT_OK, 5); /* the Ball refuses this completion */
    EXPECT("quarantine", rt.quarantined && B.faults == 1, "a refused completion did not quarantine the instance");
    EXPECT("quarantine", ba_request(&rt, PB_CALL, P_GET, NULL) == -1, "a quarantined instance accepted a request");
}

static void retry(void)
{
    /* FAILED on attempt 1 retries with a new write-ahead record; a NACK of attempt 2 says nothing about attempt 1. */
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    ba_request(&rt, PB_CALL, P_SET, NULL);
    uint32_t key = B.last_key;
    ba_report(&rt, key, 1, BA_REPORT_FAILED, 0);
    EXPECT("retry", B.transmissions == 2 && B.last_attempt == 2, "an Idempotent call was not retried after FAILED");
    EXPECT("retry", B.write_ahead_missing == 0, "a retry was transmitted without its own write-ahead record");
    ba_report(&rt, key, 2, BA_REPORT_NOT_APPLIED, 0);
    EXPECT("retry", B.completions == 1 && B.last_outcome.kind == BA_UNKNOWN && B.last_outcome.value == BA_R_RETRIES_EXHAUSTED,
           "a NACK of the last retry ended the call NotDone, although attempt 1 may have been applied");
    /* A stale report of an earlier attempt neither retries nor completes the call. */
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET3);
    ba_request(&rt, PB_CALL, P_SET, NULL);
    key = B.last_key;
    ba_report(&rt, key, 1, BA_REPORT_FAILED, 0);
    ba_report(&rt, key, 1, BA_REPORT_FAILED, 0);
    EXPECT("retry", B.transmissions == 2 && B.completions == 0, "a stale report of attempt 1 was acted on");
}

static void nvm_error(void)
{
    /* The commit write fails: refused, quarantined, nothing sent. */
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    B.fail_write = 1;
    int r = ba_request(&rt, PB_CALL, P_ACT, NULL);
    EXPECT("nvm-error", r == -1 && rt.quarantined && B.transmissions == 0, "a decision whose commit write failed was dispatched");
    /* The write-ahead write fails: nothing sent; the record still says Pending. */
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    B.fail_write = 2;
    ba_request(&rt, PB_CALL, P_ACT, NULL);
    EXPECT("nvm-error", B.transmissions == 0 && rt.quarantined, "a call was transmitted although its write-ahead record was not stored");
    /* Restart before the deadline: the Pending call is sent, once. */
    B.fail_write = 0;
    B.clock += 100;
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    EXPECT("nvm-error", !rt.quarantined && B.transmissions == 1 && B.write_ahead_missing == 0,
           "after a restart, the Pending call was not sent exactly once with a write-ahead record");
    /* Restart past the deadline of a Pending call: NotDone(NeverSent), nothing sent. */
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    B.fail_write = 2;
    ba_request(&rt, PB_CALL, P_ACT, NULL);
    B.fail_write = 0;
    B.clock += 1000;
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    EXPECT("nvm-error", B.transmissions == 0 && B.completions == 1 && B.last_outcome.kind == BA_NOT_DONE &&
                            B.last_outcome.value == BA_R_NEVER_SENT,
           "a Pending call past its deadline did not end NotDone(NeverSent)");
}

static void ball_check(void)
{
    board_reset();
    ba_boot(&rt, &OVERSIZE, &PF, BUDGET);
    EXPECT("ball-check", rt.quarantined && ba_request(&rt, PB_CALL, P_GET, NULL) == -1, "a Ball with a 40-byte state was run");
    board_reset();
    ba_boot(&rt, &TOO_MANY_PORTS, &PF, BUDGET);
    EXPECT("ball-check", rt.quarantined, "a Ball with more than BA_MAX_PORTS ports was run");
}

static void corrupt(void)
{
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    ba_request(&rt, PB_CALL, P_ACT, NULL); /* a dose-like call, possibly sent */
    ((uint8_t *)&B.slot[0])[20] ^= 0xFF;
    ((uint8_t *)&B.slot[1])[20] ^= 0xFF;
    int sent = B.transmissions;
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    EXPECT("corrupt", rt.quarantined && ba_request(&rt, PB_CALL, P_ACT, NULL) == -1 && B.transmissions == sent,
           "with both committed slots corrupt, the instance started fresh and forgot its possibly-sent call");
    /* A torn first write leaves one slot written and invalid: nothing was ever committed. */
    board_reset();
    B.written[1] = 1;
    memset(&B.slot[1], 0xA5, sizeof B.slot[1]);
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    EXPECT("corrupt", !rt.quarantined, "a torn first write quarantined the instance");
    /* The first commit is torn after the magic word: nothing was committed, and nothing was sent. */
    board_reset();
    B.tear_write = 1;
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    ba_request(&rt, PB_CALL, P_ACT, NULL);
    B.tear_write = 0;
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    EXPECT("corrupt", !rt.quarantined && B.transmissions == 0,
           "a first commit torn after the magic word was taken for another build's newer record");
    /* The commit is stored in slot 1; the write-ahead record, slot 0's first write, is torn after the magic word. */
    board_reset();
    B.tear_write = 2;
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    ba_request(&rt, PB_CALL, P_ACT, NULL);
    B.tear_write = 0;
    B.clock += 100;
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    EXPECT("corrupt", !rt.quarantined && B.transmissions == 1 && B.write_ahead_missing == 0,
           "a write-ahead record torn after the magic word hid the committed record next to it");
}

/* A committed record of `ball` with no open call, marked `mark`, and sequence number `seq`, as that build would write it. */
static ba_record record_of(const ba_ball *ball, uint8_t mark, uint32_t seq)
{
    board_reset();
    ba_boot(&rt, ball, &PF, BUDGET);
    ba_request(&rt, PB_NOTE, mark, NULL);
    ba_record r = *durable();
    r.seq = seq;
    r.head_crc = ba_crc32(&r, offsetof(ba_record, head_crc));
    r.crc = ba_crc32(&r, offsetof(ba_record, crc));
    return r;
}

static void layout(void)
{
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    ba_request(&rt, PB_CALL, P_ACT, NULL);
    int sent = B.transmissions;
    ba_boot(&rt, &PROBE_V2, &PF, BUDGET); /* same size, new schema */
    EXPECT("layout", rt.quarantined && B.transmissions == sent, "a record written for schema 1 was loaded by schema 2");
    /* After a migration, the other slot still holds an older record of the old schema: it is stale, not a fault. */
    ba_record v1 = record_of(&PROBE, 1, 3), v2 = record_of(&PROBE_V2, 2, 4);
    board_reset();
    B.slot[0] = v1;
    B.slot[1] = v2;
    B.written[0] = B.written[1] = 1;
    ba_boot(&rt, &PROBE_V2, &PF, BUDGET);
    EXPECT("layout", !rt.quarantined && rt.rec.seq == 4 && ((const pb_state *)ba_state(&rt))->completions == 2,
           "a migrated record was not loaded next to an older record of the old schema");
    /* A record of the old schema that is newer than ours: its ledger is the latest one. */
    v1 = record_of(&PROBE, 1, 5);
    board_reset();
    B.slot[0] = v1;
    B.slot[1] = v2;
    B.written[0] = B.written[1] = 1;
    ba_boot(&rt, &PROBE_V2, &PF, BUDGET);
    EXPECT("layout", rt.quarantined, "an older record of this schema was loaded although a newer one of another schema exists");
}

static void state_copy(void)
{
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    ba_request(&rt, PB_CALL, P_GET, NULL);
    uint8_t before = rt.rec.state[0];
    ba_request(&rt, PB_WRITE_STATE, 0, NULL);
    EXPECT("state-copy", rt.rec.state[0] == before, "a decider changed the committed state by casting away const");
}

/* The next build of the probe, with ports 1 and 2 declared the other way round: P_ACT Idempotent, P_SET NonIdempotent. */
static const ba_effect PB_EFFECTS_SWAPPED[BA_MAX_PORTS + 1] = {BA_SAFE, BA_IDEMPOTENT, BA_NON_IDEMPOTENT, BA_SAFE};
static const ba_ball PROBE_SWAPPED = {"probe", 1u, sizeof(pb_state), P_PORTS, PB_EFFECTS_SWAPPED, pb_initial, pb_step};
static const uint8_t BUDGET_ALL[P_PORTS] = {0, 2, 2, 2};

/* The next build of the probe, with P_SET declared Safe. */
static const ba_effect PB_EFFECTS_SET_SAFE[BA_MAX_PORTS + 1] = {BA_SAFE, BA_NON_IDEMPOTENT, BA_SAFE, BA_SAFE};
static const ba_ball PROBE_SET_SAFE = {"probe", 1u, sizeof(pb_state), P_PORTS, PB_EFFECTS_SET_SAFE, pb_initial, pb_step};

/* A call on P_SET committed Idempotent and sent (and retried once, if `retried`); a restart `ms` later into PROBE_SET_SAFE. */
static void set_then_safe(int retried, uint64_t ms)
{
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET_ALL);
    ba_request(&rt, PB_CALL, P_SET, NULL);
    if (retried) ba_report(&rt, B.last_key, 1, BA_REPORT_FAILED, 0);
    B.clock += ms;
    ba_boot(&rt, &PROBE_SET_SAFE, &PF, BUDGET_ALL);
}

static int unknown_with(int32_t reason)
{
    return B.completions == 1 && B.last_outcome.kind == BA_UNKNOWN && B.last_outcome.value == reason;
}

static void effect_class(void)
{
    /* Committed NonIdempotent and possibly sent; the next build declares the port Idempotent. */
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET_ALL);
    ba_request(&rt, PB_CALL, P_ACT, NULL);
    B.clock += 100;
    ba_boot(&rt, &PROBE_SWAPPED, &PF, BUDGET_ALL);
    EXPECT("effect-class", B.transmissions == 1 && B.completions == 1 && B.last_outcome.kind == BA_UNKNOWN &&
                               B.last_outcome.value == BA_R_RECOVERED_AFTER_CRASH,
           "a call committed NonIdempotent was re-sent after a restart because its port is now Idempotent");
    /* Committed NonIdempotent and never sent; sent after the restart, it fails and is not retried. */
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET_ALL);
    B.fail_write = 2;
    ba_request(&rt, PB_CALL, P_ACT, NULL);
    B.fail_write = 0;
    B.clock += 100;
    ba_boot(&rt, &PROBE_SWAPPED, &PF, BUDGET_ALL);
    ba_report(&rt, B.last_key, 1, BA_REPORT_FAILED, 0);
    EXPECT("effect-class", B.transmissions == 1 && B.completions == 1 && B.last_outcome.kind == BA_UNKNOWN &&
                               B.last_outcome.value == BA_R_LOST_AFTER_SEND,
           "a call committed NonIdempotent was retried because its port is now Idempotent");
    /* Committed Idempotent and possibly sent; the next build declares the port NonIdempotent, which allows one attempt. */
    board_reset();
    ba_boot(&rt, &PROBE, &PF, BUDGET_ALL);
    ba_request(&rt, PB_CALL, P_SET, NULL);
    B.clock += 100;
    ba_boot(&rt, &PROBE_SWAPPED, &PF, BUDGET_ALL);
    EXPECT("effect-class", B.transmissions == 1 && B.completions == 1 && B.last_outcome.kind == BA_UNKNOWN &&
                               B.last_outcome.value == BA_R_RETRIES_EXHAUSTED,
           "a call committed Idempotent was re-sent, or not classified as Idempotent, after its port became NonIdempotent");

    /* Committed Idempotent and possibly sent; the next build declares the port Safe, so a NotDone would deny attempt 1. */
    set_then_safe(0, 100); /* attempt 2 is sent at boot */
    EXPECT("effect-class", B.transmissions == 2 && B.last_attempt == 2, "a call committed Idempotent was not re-sent at boot");
    B.clock += 300;
    ba_tick(&rt);
    EXPECT("effect-class", unknown_with(BA_R_DEADLINE_AFTER_SEND),
           "a call committed Idempotent ended NotDone at its deadline after its port became Safe");
    set_then_safe(0, 100);
    ba_report(&rt, B.last_key, 2, BA_REPORT_NOT_APPLIED, 0);
    EXPECT("effect-class", unknown_with(BA_R_RETRIES_EXHAUSTED),
           "a NACK of attempt 2 ended a call committed Idempotent NotDone after its port became Safe");
    set_then_safe(0, 400); /* the restart comes after the deadline */
    EXPECT("effect-class", B.transmissions == 1 && unknown_with(BA_R_DEADLINE_AFTER_SEND),
           "a restart past its deadline ended a call committed Idempotent NotDone after its port became Safe");
    set_then_safe(1, 100); /* the restart comes with the budget spent */
    EXPECT("effect-class", B.transmissions == 2 && unknown_with(BA_R_RETRIES_EXHAUSTED),
           "a restart with its budget spent ended a call committed Idempotent NotDone after its port became Safe");
}

static void wear(void)
{
    char what[120];
    for (uint8_t port = P_ACT; port <= P_GET; port++) {
        board_reset();
        ba_boot(&rt, &PROBE, &PF, BUDGET);
        int base = B.writes;
        ba_request(&rt, PB_CALL, port, NULL);
        ba_report(&rt, B.last_key, 1, BA_REPORT_OK, 5);
        snprintf(what, sizeof what, "a call on port %u answered OK cost %d NVM writes, not 3", port, B.writes - base);
        EXPECT("wear", B.completions == 1 && B.writes - base == 3, what);
    }
    for (uint8_t port = P_SET; port <= P_GET; port++) {
        board_reset();
        ba_boot(&rt, &PROBE, &PF, BUDGET);
        int base = B.writes;
        ba_request(&rt, PB_CALL, port, NULL);
        ba_report(&rt, B.last_key, 1, BA_REPORT_FAILED, 0);
        ba_report(&rt, B.last_key, 2, BA_REPORT_OK, 5);
        snprintf(what, sizeof what, "a call on port %u retried once cost %d NVM writes, not 4", port, B.writes - base);
        EXPECT("wear", B.completions == 1 && B.writes - base == 4, what);
    }
}

static void time64(void)
{
    board_reset();
    B.clock = 0x100000000ull - 500u;
    ba_boot(&rt, &PROBE, &PF, BUDGET);
    ba_request(&rt, PB_TIMER, 1000, NULL); /* due at 2^32 + 500 */
    for (uint64_t t = B.clock; t < 0x100000000ull + 400u; t += 100) {
        B.clock = t;
        ba_tick(&rt);
    }
    EXPECT("time", B.completions == 0, "a timer due after the 32-bit wrap fired before its due time");
    B.clock = 0x100000000ull + 500u;
    ba_tick(&rt);
    EXPECT("time", B.completions == 1 && B.last_outcome.kind == BA_DONE, "a timer did not fire at its due time");
}

int main(void)
{
    preflight();
    quarantine();
    retry();
    nvm_error();
    ball_check();
    corrupt();
    layout();
    state_copy();
    effect_class();
    wear();
    time64();
    if (failures) return 1;
    printf("runtime tests: preflight, keys, quarantine, retries, NVM write errors, Ball bounds, corrupt and foreign records, "
           "state copy, recorded effect classes, NVM writes per call and 64-bit time; no violation\n");
    return 0;
}
