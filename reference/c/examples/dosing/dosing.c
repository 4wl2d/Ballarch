/*
 * The therapy decider. Pure (rule R2): no I/O, clock, globals or libc calls;
 * tools/check_purity.sh verifies that its object file references no external
 * symbol and defines no writable data.
 *
 * Seeded bugs for the negative controls in the Makefile (never enabled in a build):
 *   TH_MUTANT_RETRY_UNKNOWN   doses again after an Unknown outcome
 *   TH_MUTANT_NO_HORIZON      reads the pump's counter without waiting out the delivery horizon (dosing.h)
 *   TH_MUTANT_SHORT_INTERVAL  waits only TH_RETRY_MS, not TH_INTERVAL_MS, after a dose was given
 *   TH_MUTANT_EXTRA_DOSE      gives one dose more than prescribed, spaced as it should be
 *   TH_MUTANT_UNKNOWN_AS_GIVEN counts a dose with an Unknown outcome as given, without asking the pump
 */
#include "dosing.h"

const ba_effect th_effects[TH_PORTS] = {BA_SAFE, BA_NON_IDEMPOTENT, BA_SAFE};

static void wait_for(th_state *n, ba_ctx *ctx, ba_outputs *out, th_stage stage, uint32_t delay)
{
    n->stage = (uint8_t)stage;
    n->call = ba_new_key(ctx);
    ba_emit(out, ba_timer(n->call, delay));
}

static void call(th_state *n, ba_ctx *ctx, ba_outputs *out, th_stage stage, uint8_t port, int32_t arg)
{
    n->stage = (uint8_t)stage;
    n->call = ba_new_key(ctx);
    ba_call c = {port, n->call, arg, TH_CALL_TIMEOUT_MS};
    ba_emit(out, c);
}

static void finish(th_state *n, th_stage stage)
{
    n->stage = (uint8_t)stage;
    n->call = 0;
}

static void dosed(th_state *n, ba_ctx *ctx, ba_outputs *out)
{
#ifdef TH_MUTANT_EXTRA_DOSE
    if (n->delivered >= n->prescribed + 1) finish(n, TH_DONE);
#else
    if (n->delivered >= n->prescribed) finish(n, TH_DONE);
#endif
#ifdef TH_MUTANT_SHORT_INTERVAL
    else wait_for(n, ctx, out, TH_WAITING, TH_RETRY_MS);
#else
    else wait_for(n, ctx, out, TH_WAITING, TH_INTERVAL_MS);
#endif
}

static ba_verdict request(const th_state *s, th_state *n, const ba_input *in, ba_ctx *ctx, ba_outputs *out, int32_t *reply)
{
    switch (in->request) {
    case TH_REQ_START:
        if (s->stage != TH_IDLE || in->arg < 1 || in->arg > TH_MAX_DOSES) {
            *reply = TH_REPLY_REFUSED;
            return BA_REJECT;
        }
        n->prescribed = (uint8_t)in->arg;
        call(n, ctx, out, TH_DOSING, TH_PORT_DOSE, n->delivered + 1);
        *reply = TH_REPLY_STARTED;
        return BA_ACCEPT;
    case TH_REQ_STATUS:
        *reply = (int32_t)(s->stage << 8 | s->delivered);
        return BA_ACCEPT;
    default:
        *reply = TH_REPLY_REFUSED;
        return BA_REJECT;
    }
}

static void completion(const th_state *s, th_state *n, const ba_input *in, ba_ctx *ctx, ba_outputs *out)
{
    if (in->key != s->call) return; /* stale */
    switch ((th_stage)s->stage) {
    case TH_DOSING:
        if (in->outcome.kind == BA_DONE) {
            n->delivered++;
            n->retries = 0;
            dosed(n, ctx, out);
        } else if (in->outcome.kind == BA_NOT_DONE) { /* evidence that no dose was given: safe to try again */
            if (++n->retries > TH_MAX_RETRIES) finish(n, TH_ALARM);
            else wait_for(n, ctx, out, TH_WAITING, TH_RETRY_MS);
        } else {
#if defined(TH_MUTANT_RETRY_UNKNOWN)
            wait_for(n, ctx, out, TH_WAITING, TH_RETRY_MS); /* treats "may have been given" as "not given" */
#elif defined(TH_MUTANT_UNKNOWN_AS_GIVEN)
            n->delivered++; /* treats "may have been given" as "given" */
            dosed(n, ctx, out);
#else
            n->queries = 0; /* never dose blindly: wait past the horizon, then ask the pump */
            wait_for(n, ctx, out, TH_SETTLING, TH_HORIZON_MS);
#endif
        }
        break;
    case TH_WAITING:
        call(n, ctx, out, TH_DOSING, TH_PORT_DOSE, n->delivered + 1);
        break;
    case TH_SETTLING:
        call(n, ctx, out, TH_QUERYING, TH_PORT_COUNTER, 0);
        break;
    case TH_QUERYING:
        if (in->outcome.kind == BA_DONE) { /* the pump's counter is the truth */
            n->delivered = (uint8_t)in->outcome.value;
            n->retries = 0;
            dosed(n, ctx, out);
        } else if (++n->queries >= TH_MAX_QUERIES) {
            finish(n, TH_ALARM); /* a person must check the pump */
        } else {
            wait_for(n, ctx, out, TH_SETTLING, TH_HORIZON_MS);
        }
        break;
    case TH_IDLE:
    case TH_DONE:
    case TH_ALARM:
        break;
    }
}

ba_verdict th_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    const th_state *s = state;
    th_state *n = next;
    *n = *s;
    if (in->kind == BA_IN_REQUEST) return request(s, n, in, ctx, out, reply);
    completion(s, n, in, ctx, out);
    return BA_ACCEPT;
}

void th_initial(void *state)
{
    th_state *s = state;
    th_state zero = {TH_IDLE, 0, 0, 0, 0, 0};
    *s = zero;
}
