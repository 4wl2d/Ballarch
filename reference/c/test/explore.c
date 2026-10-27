/*
 * The bounded exhaustive explorer described in explore.h. It checks the
 * runtime rules against the simulated board in every run:
 *   write-ahead  R5/R8: NVM holds the call as Sending, with this attempt, when it is transmitted
 *   resend       R8: a NonIdempotent call is transmitted at most once
 *   completion   R6: every committed call completes exactly once
 *   outcome      R7: NotDone only if the device did not apply the request; Done on an effectful port only if it did
 *   timer-due    a timer completes Done no earlier than its due time
 *   quarantine   the instance is quarantined only after an injected NVM write error
 *   commit       a call traced as committed is in the newest valid NVM record
 *   livelock     the main loop does not keep waking without time advancing
 *   bound, replay  the harness's own guards: a run that needs more room than it has, and a
 *                nondeterministic replay, fail the exploration instead of being cut short
 * The device model adds the example's own oracles.
 *
 * Seeded bug for the negative control in the Makefile (never enabled in a build):
 *   EX_MUTANT_NONDETERMINISM  changes the arity of one choice between replays
 */
#include "explore.h"

#include <setjmp.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define EX_MAX_ITERATIONS 10000L

enum { FK_POWER = 1, FK_TORN = 2, FK_WRITE_ERROR = 4 };

typedef struct {
    int prefix[EX_MAX_DEPTH], arity[EX_MAX_DEPTH];
    int len, pos;
} chooser;

static chooser CH;

/* The simulated world, cleared for every run. */
static struct {
    const ex_model *m;
    uint64_t clock;
    ba_record slot[2];
    int written[2], valid[2]; /* valid: the slot's CRC matches, computed once per write */
    int incarnation, started;
    int faults, kinds, write_error, points;
    jmp_buf on_fault;
    ex_event ev[EX_MAX_EVENTS];
    int n_ev;
    ex_call calls[EX_MAX_LOG];
    int n_calls;
    int fates, forced;
    int violations;
    char first[256];
} W;

static ba_runtime RT; /* static: survives longjmp without volatile qualifiers */
static long RUN, SHOW = -1; /* EX_SHOW=n prints the events of run n */

#define SHOWF(...) \
    do { \
        if (RUN == SHOW) { \
            printf("  t=%-6llu ", (unsigned long long)(W.clock - W.m->start_ms)); \
            printf(__VA_ARGS__); \
            printf("\n"); \
        } \
    } while (0)

uint64_t ex_now(void) { return W.clock; }

void ex_violation(const char *tag, const char *fmt, ...)
{
    if (W.violations++ == 0) {
        int n = snprintf(W.first, sizeof W.first, "[%s] ", tag);
        va_list ap;
        va_start(ap, fmt);
        if (n > 0 && (size_t)n < sizeof W.first) vsnprintf(W.first + n, sizeof W.first - (size_t)n, fmt, ap);
        va_end(ap);
    }
}

int ex_choose(int n)
{
#ifdef EX_MUTANT_NONDETERMINISM
    static long drawn;
    if (++drawn == 100000) n++;
#endif
    chooser *c = &CH;
    if (c->pos >= EX_MAX_DEPTH) {
        ex_violation("bound", "a run needs more than %d choices; raise EX_MAX_DEPTH", EX_MAX_DEPTH);
        return 0;
    }
    if (c->pos < c->len) {
        if (c->arity[c->pos] != n) {
            ex_violation("replay", "nondeterministic replay at choice %d", c->pos);
            c->pos++;
            return 0;
        }
        return c->prefix[c->pos++];
    }
    c->prefix[c->len] = 0;
    c->arity[c->len] = n;
    c->len++;
    c->pos++;
    return 0;
}

static int next_run(void)
{
    chooser *c = &CH;
    if (c->len > c->pos) c->len = c->pos;
    while (c->len > 0 && c->prefix[c->len - 1] + 1 >= c->arity[c->len - 1]) c->len--;
    if (c->len == 0) return 0;
    c->prefix[c->len - 1]++;
    c->pos = 0;
    return 1;
}

int ex_fate(int n)
{
    if (W.fates >= W.m->fate_bound) {
        W.forced++;
        return 0;
    }
    W.fates++;
    return ex_choose(n);
}

ex_call *ex_log(uint32_t key)
{
    for (int i = 0; i < W.n_calls; i++)
        if (W.calls[i].key == key) return &W.calls[i];
    if (W.n_calls == EX_MAX_LOG) {
        ex_violation("bound", "more calls than the test can log");
        return &W.calls[EX_MAX_LOG - 1];
    }
    ex_call fresh;
    memset(&fresh, 0, sizeof fresh);
    fresh.key = key;
    W.calls[W.n_calls] = fresh;
    return &W.calls[W.n_calls++];
}

void ex_schedule(uint64_t delay, uint32_t key, uint8_t attempt, int kind, int32_t value)
{
    if (W.n_ev == EX_MAX_EVENTS) {
        ex_violation("bound", "more link events than the test can hold");
        return;
    }
    ex_event e = {W.clock + delay, key, attempt, (uint8_t)kind, value, W.incarnation, 1};
    W.ev[W.n_ev++] = e;
}

const ba_record *ex_durable(void)
{
    const ba_record *best = NULL;
    for (int s = 0; s < 2; s++) {
        const ba_record *r = &W.slot[s];
        if (!W.valid[s]) continue;
        if (best == NULL || r->seq > best->seq) best = r;
    }
    return best;
}

/* ---------------------------------------------------------------- faults */

/* A fault opportunity with n kinds of fault; 0 means none. */
static int fault_choice(int n)
{
    W.points++;
    if (W.faults >= EX_FAULTS) return 0;
    return ex_choose(n + 1);
}

static _Noreturn void restart(int kind)
{
    SHOWF("fault %d: restart", kind);
    W.faults++;
    W.kinds |= kind;
    longjmp(W.on_fault, 1);
}

/* A possible power-loss point. */
static void point(void)
{
    if (fault_choice(1)) restart(FK_POWER);
}

/* ---------------------------------------------------------------- the board */

static int nvm_write(void *u, int slot, const void *data, size_t n)
{
    (void)u;
    switch (fault_choice(3)) {
    case 1: /* power lost before the write: the slot is unchanged */
        restart(FK_POWER);
    case 2: { /* power lost during the write: the slot was erased and only a prefix was programmed */
        static const size_t kept[3] = {sizeof(uint32_t), offsetof(ba_record, revision), 0}; /* magic, header, half */
        size_t k = kept[ex_choose(3)];
        if (k == 0 || k > n) k = n / 2;
        memset(&W.slot[slot], 0xFF, sizeof W.slot[slot]);
        memcpy(&W.slot[slot], data, k);
        W.written[slot] = 1;
        W.valid[slot] = W.slot[slot].crc == ba_crc32(&W.slot[slot], offsetof(ba_record, crc));
        SHOWF("NVM write to slot %d torn after %u bytes", slot, (unsigned)k);
        restart(FK_TORN);
    }
    case 3: /* the write fails and says so; the slot is unchanged */
        SHOWF("NVM write fails");
        W.faults++;
        W.kinds |= FK_WRITE_ERROR;
        W.write_error = 1;
        return -1;
    default:
        break;
    }
    memcpy(&W.slot[slot], data, n);
    W.written[slot] = 1;
    W.valid[slot] = W.slot[slot].crc == ba_crc32(&W.slot[slot], offsetof(ba_record, crc));
    if (W.m->committed) W.m->committed(&W.slot[slot]);
    return 0;
}

static int nvm_read(void *u, int slot, void *data, size_t n)
{
    (void)u;
    if (!W.written[slot]) return 0;
    memcpy(data, &W.slot[slot], n);
    return 1;
}

static uint64_t now(void *u)
{
    (void)u;
    return W.clock;
}

static const ba_call_rec *durable_call(uint32_t key)
{
    const ba_record *r = ex_durable();
    for (unsigned i = 0; r && i < BA_MAX_CALLS; i++)
        if (r->calls[i].used && r->calls[i].key == key) return &r->calls[i];
    return NULL;
}

static void transmit(void *u, uint8_t port, uint32_t key, int32_t arg, uint8_t attempt)
{
    (void)u;
    point(); /* lost after the write-ahead record, before the bytes leave */
    const ba_call_rec *c = durable_call(key);
    if (c == NULL || c->phase != BA_SENDING || c->attempts != attempt)
        ex_violation("write-ahead", "call %u attempt %u transmitted before NVM recorded it as Sending", key, attempt);
    ex_call *l = ex_log(key);
    SHOWF("transmit port %u key %u arg %d attempt %u", port, key, arg, attempt);
    l->transmissions++;
    l->port = port;
    if (W.m->ball->effects[port] == BA_NON_IDEMPOTENT && l->transmissions > 1)
        ex_violation("resend", "NonIdempotent call %u transmitted %d times", key, l->transmissions);
    W.m->transmit(l, port, key, arg, attempt);
}

static void trace(void *u, int event, uint32_t key, const ba_outcome *o)
{
    (void)u;
    if (event == BA_T_FAULT) {
        if (W.write_error) longjmp(W.on_fault, 1); /* the quarantined controller is reset, as after a power loss */
        ex_violation("quarantine", "instance quarantined at call %u without an NVM write error", key);
        return;
    }
    ex_call *l = ex_log(key);
    if (event == BA_T_CALL_COMMITTED) {
        l->committed++;
        const ba_call_rec *c = durable_call(key);
        if (c == NULL) ex_violation("commit", "call %u traced as committed, but NVM does not hold it", key);
        else if (c->port == BA_TIMER_PORT) {
            l->timer = 1;
            l->port = BA_TIMER_PORT;
            l->due = W.clock + (uint32_t)c->arg;
        } else
            l->port = c->port;
    }
    if (event == BA_T_COMPLETED) {
        SHOWF("complete key %u kind %d value %d", key, o->kind, o->value);
        l->completed++;
        l->outcome = *o;
        if (l->completed > 1) ex_violation("completion", "call %u completed twice", key);
        if (!l->committed) ex_violation("completion", "completion for call %u, which was never committed", key);
        if (l->timer && o->kind == BA_DONE && W.clock < l->due)
            ex_violation("timer-due", "timer %u fired %llu ms before its due time", key, (unsigned long long)(l->due - W.clock));
    }
}

static const ba_platform PLATFORM = {NULL, nvm_write, nvm_read, transmit, now, trace};

static uint64_t next_event(void)
{
    uint64_t t = UINT64_MAX;
    for (int i = 0; i < W.n_ev; i++)
        if (W.ev[i].live && W.ev[i].at < t) t = W.ev[i].at;
    return t;
}

/* Delayed requests reach the device at their time, in time order, whether or not the controller runs. */
static void device_until(uint64_t t)
{
    for (;;) {
        ex_event *first = NULL;
        for (int i = 0; i < W.n_ev; i++) {
            ex_event *e = &W.ev[i];
            if (e->live && e->kind == EX_K_APPLY && e->at <= t && (first == NULL || e->at < first->at)) first = e;
        }
        if (first == NULL) return;
        first->live = 0;
        if (first->at > W.clock) W.clock = first->at;
        SHOWF("event key %u kind %u (controller off)", first->key, first->kind);
        W.m->apply(ex_log(first->key), first);
    }
}

/* ---------------------------------------------------------------- one run */

typedef struct {
    long runs, power, torn, write_error, forced_runs, forced;
    long finals[8];
    int max_points, max_depth;
} totals;

static void run(const ex_model *m, totals *t)
{
    memset(&W, 0, sizeof W);
    memset(W.slot, 0xFF, sizeof W.slot); /* erased flash */
    W.m = m;
    W.clock = m->start_ms;
    if (m->reset) m->reset();
    if (setjmp(W.on_fault)) { /* power lost or controller reset: RAM is gone; it restarts later */
        W.incarnation++;
        W.write_error = 0;
        uint64_t up = W.clock + (ex_choose(2) ? EX_LONG_REBOOT_MS : EX_REBOOT_MS);
        device_until(up); /* the device keeps its power while the controller is off */
        W.clock = up;
        SHOWF("boot");
    }
    ba_boot(&RT, m->ball, &PLATFORM, m->budget);
    if (!W.started) {
        W.started = 1;
        m->start(&RT); /* first power-on only */
    }
    long iterations = 0;
    int stalled = 0;
    for (;;) {
        uint64_t a = ba_next_wakeup(&RT), b = next_event();
        uint64_t at = a < b ? a : b;
        if (at == UINT64_MAX || at > m->end_ms) break;
        int idle = at > W.clock + EX_IDLE_TICK_MS;
        if (idle) at = W.clock + EX_IDLE_TICK_MS;
        if (at > W.clock) {
            W.clock = at;
            stalled = 0;
        } else
            stalled++;
        if (stalled > EX_MAX_STALL || ++iterations > EX_MAX_ITERATIONS) {
            ex_violation("livelock", "the main loop keeps waking at t=%llu without time advancing", (unsigned long long)(W.clock - m->start_ms));
            break;
        }
        if (!idle) point();
        for (int i = 0; i < W.n_ev; i++) {
            ex_event *e = &W.ev[i];
            if (!e->live || e->at > W.clock) continue;
            e->live = 0;
            SHOWF("event key %u kind %u value %d%s", e->key, e->kind, e->value, e->incarnation == W.incarnation || e->kind == EX_K_APPLY ? "" : " (dropped)");
            if (e->kind == EX_K_APPLY) m->apply(ex_log(e->key), e); /* the device keeps its power */
            else if (e->incarnation == W.incarnation) ba_report(&RT, e->key, e->attempt, (ba_report_kind)e->kind, e->value);
        }
        ba_tick(&RT);
    }

    /* Oracles at quiescence. */
    for (int i = 0; i < W.n_calls; i++) {
        const ex_call *l = &W.calls[i];
        if (l->committed && !l->completed) ex_violation("completion", "call %u never completed", l->key);
        if (!l->completed || l->timer) continue;
        if (l->outcome.kind == BA_NOT_DONE && l->applied) ex_violation("outcome", "call %u: NotDone, but the device applied it", l->key);
        if (l->outcome.kind == BA_DONE && m->ball->effects[l->port] != BA_SAFE && !l->applied)
            ex_violation("outcome", "call %u: Done, but the device never applied it", l->key);
    }
    int cls = m->check(&RT);
    if (cls >= 0 && cls < 8) t->finals[cls]++;
    if (W.kinds & FK_POWER) t->power++;
    if (W.kinds & FK_TORN) t->torn++;
    if (W.kinds & FK_WRITE_ERROR) t->write_error++;
    if (W.forced) {
        t->forced_runs++;
        t->forced += W.forced;
    }
    if (W.incarnation == 0 && W.points > t->max_points) t->max_points = W.points;
    if (CH.pos > t->max_depth) t->max_depth = CH.pos;
    t->runs++;
}

int ex_explore(const ex_model *m)
{
    memset(&CH, 0, sizeof CH);
    const char *show = getenv("EX_SHOW");
    if (show) SHOW = atol(show);
    totals t;
    memset(&t, 0, sizeof t);
    int bad = 0;
    char first[256] = "";
    do { /* stops at the first counterexample, like a model checker */
        RUN = t.runs + 1;
        run(m, &t);
        if (W.violations) {
            bad = 1;
            snprintf(first, sizeof first, "%s", W.first);
        }
    } while (!bad && next_run());
    printf("%s exploration: %ld runs (%ld with a power loss, %ld with a torn NVM write, %ld with a failed NVM write); final:",
           m->name, t.runs, t.power, t.torn, t.write_error);
    for (int i = 0; i < m->n_finals && i < 8; i++) printf(" %s=%ld", m->finals[i], t.finals[i]);
    printf("\n");
    printf("%s bounds: at most %d fault(s) per run, placed at each power-loss point and NVM write that a run reaches before it "
           "(up to %d in a run without a fault; a torn write keeps 4 bytes, 16 bytes or half of the record); link fates for the "
           "first %d transmissions of a run (%ld runs had %ld later transmissions, answered in time); deepest run %d of %d "
           "choices; every run is a distinct sequence of choices\n",
           m->name, EX_FAULTS, t.max_points, m->fate_bound, t.forced_runs, t.forced, t.max_depth, EX_MAX_DEPTH);
    if (bad) printf("counterexample in run %ld %s\n", t.runs, first);
    else printf("%s: no violation in any run\n", m->name);
    if (!bad && m->expect_runs && t.runs != m->expect_runs) {
        printf("%s: %ld runs, but these bounds must give %ld; update the published total only for a deliberate change\n",
               m->name, t.runs, m->expect_runs);
        bad = 1;
    }
    return bad;
}
