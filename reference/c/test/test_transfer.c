/*
 * Bounded exhaustive fault exploration of the transfer controller (see
 * explore.h for the bounds). The simulated valve controller is the ground
 * truth. Besides the runtime oracles of explore.c, every run checks:
 *   claim         R14: every claim that others could read admits the valves' final
 *                 positions: the knowledge in each committed state, and the reply that
 *                 STATUS gives in that state (the decider is pure, so the test asks it
 *                 without committing anything)
 *   seven-values  each of those claims is one of the algebra's seven values
 *   transfer      the controller settles, and is idle only if no valve is open
 * Exit status 1 means a violation, or that the default bounds no longer give the
 * published run total; the mutant builds in the Makefile must fail.
 *
 * Seeded bug for the negative control in the Makefile (never enabled in a build):
 *   TF_MUTANT_NO_FENCE  CLOSE does not fence its token, so a late OPEN reopens the
 *                       valve: the compensation is not absorbing
 */
#include "explore.h"
#include "transfer.h"

#include <stdio.h>
#include <string.h>

#define START_MS 1000u
#define END_MS (START_MS + 30000u)
#ifndef FATE_BOUND
#define FATE_BOUND 7 /* link fates explored for the first FATE_BOUND transmissions of a run */
#endif
#if FATE_BOUND == 7 && EX_FAULTS == 1
#define EXPECT_RUNS 982599L /* the published total of the default bounds */
#else
#define EXPECT_RUNS 0L /* other bounds: not checked */
#endif
#define DELAY_MS 500u /* a delayed OPEN reaches the valve controller after the call's deadline */

enum { O_REPLY, O_REPLY_LATE, O_LOSE_REQUEST, O_LOSE_REPLY, O_ERROR_AFTER_APPLY, O_NACK, O_DELAYED_REQUEST, OPEN_FATES };
enum { K_REPLY, K_LOSE_REQUEST, K_LOSE_REPLY, K_ERROR_AFTER_APPLY, K_NACK, CLOSE_FATES };

/* The valve controller: the ground truth. It keeps its power when the transfer controller loses its. */
static struct {
    uint32_t open_by[2]; /* token of the OPEN that opened each valve; 0 if closed (keys start at 16) */
    uint32_t fenced[EX_MAX_LOG];
    int n_fenced;
    unsigned claims; /* bit 3 * all + any: the claims committed in this run */
} V;

static void valves_reset(void) { memset(&V, 0, sizeof V); }

static int is_fenced(uint32_t token)
{
    for (int i = 0; i < V.n_fenced; i++)
        if (V.fenced[i] == token) return 1;
    return 0;
}

/* OPEN(token) reaches the valve controller. Returns 0 if it refused a fenced token. */
static int valve_open(ex_call *l, uint32_t token, int32_t valve)
{
    if (is_fenced(token)) return 0;
    V.open_by[valve & 1] = token;
    l->applied = 1;
    return 1;
}

/* CLOSE(token): closes what that OPEN opened and refuses that OPEN from now on. */
static void valve_close(ex_call *l, uint32_t token)
{
#ifndef TF_MUTANT_NO_FENCE
    if (!is_fenced(token) && V.n_fenced < EX_MAX_LOG) V.fenced[V.n_fenced++] = token;
#endif
    for (int v = 0; v < 2; v++)
        if (V.open_by[v] == token) V.open_by[v] = 0;
    l->applied = 1;
}

static void open_answer(uint64_t delay, uint32_t key, uint8_t attempt, int opened)
{
    if (opened) ex_schedule(delay, key, attempt, BA_REPORT_OK, TF_OPENED);
    else ex_schedule(delay, key, attempt, BA_REPORT_REFUSED, 0);
}

static void valves_transmit(ex_call *l, uint8_t port, uint32_t key, int32_t arg, uint8_t attempt)
{
    if (port == TF_PORT_OPEN) {
        switch (ex_fate(OPEN_FATES)) {
        case O_REPLY: open_answer(EX_LATENCY_MS, key, attempt, valve_open(l, key, arg)); break;
        case O_REPLY_LATE: open_answer(TF_CALL_TIMEOUT_MS + EX_LATENCY_MS, key, attempt, valve_open(l, key, arg)); break;
        case O_LOSE_REQUEST: break;
        case O_LOSE_REPLY: valve_open(l, key, arg); break;
        case O_ERROR_AFTER_APPLY: valve_open(l, key, arg); ex_schedule(EX_LATENCY_MS, key, attempt, BA_REPORT_FAILED, 0); break;
        case O_NACK: ex_schedule(EX_LATENCY_MS, key, attempt, BA_REPORT_NOT_APPLIED, 0); break;
        case O_DELAYED_REQUEST: ex_schedule(DELAY_MS, key, attempt, EX_K_APPLY, arg); break; /* arrives late, never answered */
        }
    } else {
        uint32_t token = (uint32_t)arg;
        switch (ex_fate(CLOSE_FATES)) {
        case K_REPLY: valve_close(l, token); ex_schedule(EX_LATENCY_MS, key, attempt, BA_REPORT_OK, 0); break;
        case K_LOSE_REQUEST: break;
        case K_LOSE_REPLY: valve_close(l, token); break;
        case K_ERROR_AFTER_APPLY: valve_close(l, token); ex_schedule(EX_LATENCY_MS, key, attempt, BA_REPORT_FAILED, 0); break;
        case K_NACK: ex_schedule(EX_LATENCY_MS, key, attempt, BA_REPORT_NOT_APPLIED, 0); break;
        }
    }
}

static void valves_apply(ex_call *l, const ex_event *e) { valve_open(l, e->key, e->value); }

static void start(ba_runtime *rt)
{
    int32_t reply = 0;
    ba_request(rt, TF_REQ_START, 0, &reply);
}

static void claimed(ba_knowledge k, const char *where, unsigned stage)
{
    if (!ba_k_valid(k)) ex_violation("seven-values", "%s in stage %u: (%u, %u) is not one of the seven values", where, stage, k.all, k.any);
    else V.claims |= 1u << (k.all * 3 + k.any);
}

/* Every committed state can be read by others, through its claim and through STATUS: both must hold. */
static void committed(const ba_record *r)
{
    tf_state s, n;
    memcpy(&s, r->state, sizeof s);
    if (s.stage == TF_IDLE) return; /* no transfer was requested: no claim */
    claimed(s.claim, "committed claim", s.stage);
    ba_outputs out;
    memset(&out, 0, sizeof out);
    ba_ctx ctx = {ex_now(), r->revision + 1u, 0};
    ba_input status = {BA_IN_REQUEST, TF_REQ_STATUS, 0, 0, 0, {BA_DONE, 0}};
    int32_t reply = -1;
    if (tf_step(&s, &status, &ctx, &n, &out, &reply) != BA_ACCEPT || out.n != 0 || (reply >> 8) != s.stage)
        ex_violation("claim", "STATUS in stage %u replied %ld, or made a call", s.stage, (long)reply);
    else
        claimed(BA_KNOWLEDGE((reply >> 4) & 0xF, reply & 0xF), "STATUS reply", s.stage);
}

enum { FINAL_IDLE = 7 };
static const char *const FINALS[] = {"NotDone", "Done", "invalid", "Partial", "NotAllDone", "SomeDone", "Unknown", "idle"};

static int final_class(ba_knowledge k)
{
    if (!ba_k_valid(k)) return 2;
    if (k.all == BA_NO) return k.any == BA_NO ? 0 : k.any == BA_YES ? 3 : 4;
    if (k.all == BA_YES) return 1;
    return k.any == BA_YES ? 5 : 6;
}

static int check(const ba_runtime *rt)
{
    tf_state s;
    memcpy(&s, ba_state(rt), sizeof s);
    int source = V.open_by[TF_SOURCE] != 0, destination = V.open_by[TF_DESTINATION] != 0;
    if (s.stage == TF_IDLE) {
        if (source || destination) ex_violation("transfer", "idle at the end, but a valve is open");
        return FINAL_IDLE;
    }
    if (s.stage != TF_SETTLED) ex_violation("transfer", "unfinished in stage %d", s.stage);
    for (unsigned c = 0; c < 9; c++) {
        if (!(V.claims & (1u << c))) continue;
        ba_knowledge k = BA_KNOWLEDGE(c / 3, c % 3);
        if (!ba_k_admits(k, source && destination, source || destination))
            ex_violation("claim", "claimed %s, but at the end source=%s destination=%s", ba_k_name(k), source ? "open" : "closed",
                         destination ? "open" : "closed");
    }
    return final_class(s.claim);
}

static const uint8_t BUDGET[TF_PORTS] = {0, 0, 2}; /* a CLOSE that fails is repeated once by the runtime */

static const ex_model MODEL = {
    "transfer", &tf_ball, BUDGET, FATE_BOUND, START_MS, END_MS, FINALS, 8,
    valves_reset, start, valves_transmit, valves_apply, committed, check, EXPECT_RUNS,
};

/* ---------------------------------------------------------------- decider unit tests */

static int unit_tests(void)
{
    int failures = 0;
    tf_state s, n;
    tf_initial(&s);
    if (!ba_k_eq(tf_claim(&s), BA_K_NO_EFFECTS)) failures++;
    ba_outputs out;
    memset(&out, 0, sizeof out);
    ba_ctx ctx = {0, 1, 0};
    int32_t reply = 0;
    ba_input start_in = {BA_IN_REQUEST, TF_REQ_START, 0, 0, 0, {BA_DONE, 0}};
    if (tf_step(&s, &start_in, &ctx, &n, &out, &reply) != BA_ACCEPT || out.n != 1 || out.call[0].port != TF_PORT_OPEN ||
        out.call[0].arg != TF_SOURCE)
        failures++;
    /* Nothing is established while the source valve is opening and the destination may still be opened. */
    if (!ba_k_eq(n.claim, BA_K_UNKNOWN)) failures++;
    ba_input status = {BA_IN_REQUEST, TF_REQ_STATUS, 0, 0, 0, {BA_DONE, 0}};
    tf_state m;
    memset(&out, 0, sizeof out);
    if (tf_step(&n, &status, &ctx, &m, &out, &reply) != BA_ACCEPT || reply != TF_STATUS(TF_OPENING_SOURCE, BA_K_UNKNOWN)) failures++;
    /* The source opened and the destination was refused: not all happened, and the source may still be closed. */
    tf_state a = n, b, c;
    ba_ctx ctx2 = {100, 2, 0};
    ba_input opened = {BA_IN_COMPLETION, 0, 0, TF_PORT_OPEN, a.call, {BA_DONE, TF_OPENED}};
    memset(&out, 0, sizeof out);
    tf_step(&a, &opened, &ctx2, &b, &out, &reply);
    ba_ctx ctx3 = {200, 3, 0};
    ba_input refused = {BA_IN_COMPLETION, 0, 0, TF_PORT_OPEN, b.call, {BA_NOT_DONE, BA_R_REFUSED}};
    memset(&out, 0, sizeof out);
    tf_step(&b, &refused, &ctx3, &c, &out, &reply);
    if (c.stage != TF_CLOSING_SOURCE || out.n != 1 || out.call[0].port != TF_PORT_CLOSE || !ba_k_eq(c.claim, BA_K_NOT_ALL_DONE))
        failures++;
    if (failures) printf("counterexample in run 0 [unit]: %d transfer decider unit tests failed\n", failures);
    return failures;
}

int main(void)
{
    int failed = unit_tests() != 0; /* the exploration runs regardless, so each mutant shows what kills it there */
    failed |= ex_explore(&MODEL) != 0;
    return failed;
}
