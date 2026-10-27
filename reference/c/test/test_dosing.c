/*
 * Bounded exhaustive fault exploration of the therapy controller on the
 * firmware runtime (see explore.h for the bounds). The simulated pump is the
 * ground truth. Besides the runtime oracles of explore.c, every run checks:
 *   spacing  doses the pump applies are at least TH_INTERVAL_MS apart
 *   overdose the pump never gives more than the prescription
 *   therapy  the final state matches the pump (done: all doses; alarm: no
 *            more believed than given; idle: none given)
 * Every run starts 1.5 s before a 32-bit millisecond counter would wrap, so its
 * timers and deadlines straddle that boundary.
 * Exit status 1 means a rule or a safety property was violated, or that the
 * default bounds no longer give the published run total; the mutant builds in
 * the Makefile must fail.
 */
#include "dosing.h"
#include "explore.h"

#include <stdio.h>
#include <string.h>

#define PRESCRIBED 2
#define START_MS (0x100000000ull - 1500u) /* 1.5 s before a 32-bit millisecond counter wraps */
#define END_MS (START_MS + 30000u)
#ifndef FATE_BOUND
#define FATE_BOUND 6 /* link fates explored for the first FATE_BOUND transmissions of a run */
#endif
#if FATE_BOUND == 6 && EX_FAULTS == 1
#define EXPECT_RUNS 937229L /* the published total of the default bounds */
#else
#define EXPECT_RUNS 0L /* other bounds: not checked */
#endif
#define DELAY_MS 500u /* a delayed dose request arrives within the link's delivery horizon */

enum { F_REPLY, F_REPLY_LATE, F_LOSE_REQUEST, F_LOSE_REPLY, F_ERROR_AFTER_APPLY, F_NACK, F_DELAYED_REQUEST, DOSE_FATES };
enum { C_REPLY, C_LOSE, C_FAILED, COUNTER_FATES };

/* The pump: the ground truth. It keeps its power when the controller loses its. */
static struct {
    int doses;
    int dosed_before;
    uint64_t last_dose_at;
} P;

static void pump_reset(void) { memset(&P, 0, sizeof P); }

static void pump_doses(ex_call *l)
{
    uint64_t t = ex_now();
    if (P.dosed_before && t - P.last_dose_at < TH_INTERVAL_MS)
        ex_violation("spacing", "doses %d and %d given %llu ms apart (minimum %u)", P.doses, P.doses + 1,
                     (unsigned long long)(t - P.last_dose_at), TH_INTERVAL_MS);
    P.doses++;
    P.dosed_before = 1;
    P.last_dose_at = t;
    l->applied = 1;
}

static void pump_transmit(ex_call *l, uint8_t port, uint32_t key, int32_t arg, uint8_t attempt)
{
    (void)arg;
    if (port == TH_PORT_DOSE) {
        switch (ex_fate(DOSE_FATES)) {
        case F_REPLY: pump_doses(l); ex_schedule(EX_LATENCY_MS, key, attempt, BA_REPORT_OK, P.doses); break;
        case F_REPLY_LATE: pump_doses(l); ex_schedule(TH_CALL_TIMEOUT_MS + EX_LATENCY_MS, key, attempt, BA_REPORT_OK, P.doses); break;
        case F_LOSE_REQUEST: break;
        case F_LOSE_REPLY: pump_doses(l); break;
        case F_ERROR_AFTER_APPLY: pump_doses(l); ex_schedule(EX_LATENCY_MS, key, attempt, BA_REPORT_FAILED, 0); break;
        case F_NACK: ex_schedule(EX_LATENCY_MS, key, attempt, BA_REPORT_NOT_APPLIED, 0); break;
        case F_DELAYED_REQUEST: ex_schedule(DELAY_MS, key, attempt, EX_K_APPLY, 0); break; /* applied late, never answered */
        }
    } else {
        switch (ex_fate(COUNTER_FATES)) {
        case C_REPLY: ex_schedule(EX_LATENCY_MS, key, attempt, BA_REPORT_OK, P.doses); break;
        case C_LOSE: break;
        case C_FAILED: ex_schedule(EX_LATENCY_MS, key, attempt, BA_REPORT_FAILED, 0); break; /* garbled: retried */
        }
    }
}

static void pump_apply(ex_call *l, const ex_event *e)
{
    (void)e;
    pump_doses(l);
}

static void start(ba_runtime *rt)
{
    int32_t reply = 0;
    ba_request(rt, TH_REQ_START, PRESCRIBED, &reply); /* the nurse presses start once */
}

enum { FINAL_DONE, FINAL_ALARM, FINAL_IDLE, FINAL_OTHER };
static const char *const FINALS[] = {"done", "alarm", "idle"};

static int check(const ba_runtime *rt)
{
    th_state s;
    memcpy(&s, ba_state(rt), sizeof s);
    if (P.doses > PRESCRIBED) ex_violation("overdose", "the pump gave %d doses", P.doses);
    switch ((th_stage)s.stage) {
    case TH_DONE:
        if (P.doses != PRESCRIBED) ex_violation("therapy", "therapy done, but the pump gave %d doses", P.doses);
        return FINAL_DONE;
    case TH_ALARM:
        if (s.delivered > P.doses) ex_violation("therapy", "believes %d doses given; the pump gave %d", s.delivered, P.doses);
        return FINAL_ALARM;
    case TH_IDLE:
        if (P.doses != 0) ex_violation("therapy", "idle, but the pump gave %d doses", P.doses);
        return FINAL_IDLE;
    default:
        ex_violation("therapy", "unfinished in stage %d", s.stage);
        return FINAL_OTHER;
    }
}

static const uint8_t BUDGET[TH_PORTS] = {0, 0, 2}; /* a garbled counter query is retried once */

static const ex_model MODEL = {
    "dosing", &th_ball, BUDGET, FATE_BOUND, START_MS, END_MS, FINALS, 3,
    pump_reset, start, pump_transmit, pump_apply, NULL, check, EXPECT_RUNS,
};

/* ---------------------------------------------------------------- decider unit tests */

static int unit_tests(void)
{
    th_state s, n;
    th_initial(&s);
    ba_outputs out;
    memset(&out, 0, sizeof out);
    ba_ctx ctx = {0, 1, 0};
    int32_t reply = 0;
    ba_input start_in = {BA_IN_REQUEST, TH_REQ_START, 2, 0, 0, {BA_DONE, 0}};
    int failures = 0;
    if (th_step(&s, &start_in, &ctx, &n, &out, &reply) != BA_ACCEPT || out.n != 1 || out.call[0].port != TH_PORT_DOSE) failures++;
    /* An unknown dose leads to waiting for the horizon, never to another dose. */
    th_state dosing = n, after;
    memset(&out, 0, sizeof out);
    ba_ctx ctx2 = {500, 2, 0};
    ba_input unknown = {BA_IN_COMPLETION, 0, 0, TH_PORT_DOSE, dosing.call, {BA_UNKNOWN, BA_R_LOST_AFTER_SEND}};
    th_step(&dosing, &unknown, &ctx2, &after, &out, &reply);
    if (after.stage != TH_SETTLING || out.n != 1 || out.call[0].port != BA_TIMER_PORT) failures++;
    if (failures) printf("counterexample in run 0 [unit]: %d decider unit tests failed\n", failures);
    return failures;
}

int main(void)
{
    int failed = unit_tests() != 0; /* the exploration runs regardless, so each mutant shows what kills it there */
    failed |= ex_explore(&MODEL) != 0;
    return failed;
}
