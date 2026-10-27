/*
 * The transfer decider. Pure (rule R2): no I/O, clock, globals or libc calls;
 * it calls only the knowledge algebra, which is pure too. tools/check_purity.sh
 * checks both object files.
 *
 * Seeded bugs for the negative controls in the Makefile (never enabled in a build):
 *   TF_MUTANT_UNKNOWN_AS_NO      treats an Unknown outcome as "no effect"
 *   TF_MUTANT_UNMADE_AS_NO       counts the destination valve as No while it may still be opened
 *                                (a call the Ball has not requested yet counts as No)
 *   TF_MUTANT_PENDING_UNDO_AS_NO counts a valve as not undone while the controller may still close it
 *   TF_MUTANT_HAND_PAIR          builds the claim by hand as (source, destination), not with the algebra
 *   TF_MUTANT_STATUS_LIES        STATUS answers Done while the destination valve is still opening
 *   TF_MUTANT_NO_SETTLE          never records that the transfer ended
 *   TF_MUTANT_FORGET             returns to idle when the transfer ends, forgetting what it opened
 */
#include "transfer.h"

const ba_effect tf_effects[TF_PORTS] = {BA_SAFE, BA_NON_IDEMPOTENT, BA_IDEMPOTENT};

static ba_tri effect(const ba_outcome *o, int effective)
{
#ifdef TF_MUTANT_UNKNOWN_AS_NO
    if (o->kind == BA_UNKNOWN) return BA_NO;
#endif
    return ba_effect_of(o->kind, effective);
}

/* The claim is about the transfer's final effect (R14): what may still change counts as Maybe. */
ba_knowledge tf_claim(const tf_state *s)
{
    tf_stage st = (tf_stage)s->stage;
    if (st == TF_IDLE) return BA_K_NO_EFFECTS; /* no transfer was requested */
    int source_open = st == TF_OPENING_SOURCE;
    int destination_open = st == TF_OPENING_SOURCE || st == TF_OPENING_DESTINATION; /* open, or may still be made */
    int source_may_close = st != TF_SETTLED;
    int destination_may_close = st == TF_OPENING_SOURCE || st == TF_OPENING_DESTINATION || st == TF_CLOSING_DESTINATION;
#ifdef TF_MUTANT_UNMADE_AS_NO
    destination_open = st == TF_OPENING_DESTINATION;
#endif
#ifdef TF_MUTANT_PENDING_UNDO_AS_NO
    source_may_close = st == TF_CLOSING_SOURCE;
    destination_may_close = st == TF_CLOSING_DESTINATION;
#endif
    const tf_valve *a = &s->valve[TF_SOURCE], *b = &s->valve[TF_DESTINATION];
    ba_tri source = ba_claim((ba_tri)a->effect, source_open, (ba_tri)a->undone, source_may_close);
    ba_tri destination = ba_claim((ba_tri)b->effect, destination_open, (ba_tri)b->undone, destination_may_close);
#ifdef TF_MUTANT_HAND_PAIR
    return BA_KNOWLEDGE(source, destination);
#else
    return ba_k_plus(ba_k_single(source), ba_k_single(destination));
#endif
}

static void call(tf_state *n, ba_ctx *ctx, ba_outputs *out, tf_stage stage, uint8_t port, int32_t arg)
{
    n->stage = (uint8_t)stage;
    n->call = ba_new_key(ctx);
    ba_call c = {port, n->call, arg, TF_CALL_TIMEOUT_MS};
    ba_emit(out, c);
}

static void settle(tf_state *n)
{
#if defined(TF_MUTANT_FORGET)
    n->stage = TF_IDLE;
#elif !defined(TF_MUTANT_NO_SETTLE)
    n->stage = TF_SETTLED;
#endif
    n->call = 0;
    n->closes = 0;
}

static void open_valve(tf_state *n, ba_ctx *ctx, ba_outputs *out, int v)
{
    call(n, ctx, out, v == TF_SOURCE ? TF_OPENING_SOURCE : TF_OPENING_DESTINATION, TF_PORT_OPEN, v);
    n->valve[v].token = n->call;
}

/* Close valve v unless it is known not to be open; then go on to the source valve, or settle. */
static void close_valve(tf_state *n, ba_ctx *ctx, ba_outputs *out, int v)
{
    tf_stage stage = v == TF_SOURCE ? TF_CLOSING_SOURCE : TF_CLOSING_DESTINATION;
    const tf_valve *x = &n->valve[v];
    if (ba_net((ba_tri)x->effect, (ba_tri)x->undone) == BA_NO) {
        if (v == TF_DESTINATION) close_valve(n, ctx, out, TF_SOURCE);
        else settle(n);
        return;
    }
    n->closes = n->stage == stage ? (uint8_t)(n->closes + 1) : 1;
    call(n, ctx, out, stage, TF_PORT_CLOSE, (int32_t)x->token);
}

static void completion(const tf_state *s, tf_state *n, const ba_input *in, ba_ctx *ctx, ba_outputs *out)
{
    if (in->key != s->call) return; /* stale */
    switch ((tf_stage)s->stage) {
    case TF_OPENING_SOURCE:
        n->valve[TF_SOURCE].effect = (uint8_t)effect(&in->outcome, in->outcome.value == TF_OPENED);
        if (n->valve[TF_SOURCE].effect == BA_YES) open_valve(n, ctx, out, TF_DESTINATION);
        else close_valve(n, ctx, out, TF_SOURCE); /* settles at once if it is known closed */
        break;
    case TF_OPENING_DESTINATION:
        n->valve[TF_DESTINATION].effect = (uint8_t)effect(&in->outcome, in->outcome.value == TF_OPENED);
        if (n->valve[TF_DESTINATION].effect == BA_YES) settle(n);
        else close_valve(n, ctx, out, TF_DESTINATION);
        break;
    case TF_CLOSING_DESTINATION:
    case TF_CLOSING_SOURCE: {
        int v = s->stage == TF_CLOSING_SOURCE ? TF_SOURCE : TF_DESTINATION;
        n->valve[v].undone = (uint8_t)ba_or((ba_tri)s->valve[v].undone, effect(&in->outcome, 1));
        if (n->valve[v].undone != BA_YES && s->closes < TF_MAX_CLOSES) close_valve(n, ctx, out, v); /* try again */
        else if (v == TF_DESTINATION) close_valve(n, ctx, out, TF_SOURCE);
        else settle(n);
        break;
    }
    case TF_IDLE:
    case TF_SETTLED:
        break;
    }
}

static ba_verdict request(const tf_state *s, tf_state *n, const ba_input *in, ba_ctx *ctx, ba_outputs *out, int32_t *reply)
{
    switch (in->request) {
    case TF_REQ_START:
        if (s->stage != TF_IDLE) {
            *reply = TF_REPLY_REFUSED;
            return BA_REJECT;
        }
        open_valve(n, ctx, out, TF_SOURCE);
        *reply = TF_REPLY_STARTED;
        return BA_ACCEPT;
    case TF_REQ_STATUS:
#ifdef TF_MUTANT_STATUS_LIES
        *reply = TF_STATUS(s->stage, s->stage == TF_OPENING_DESTINATION ? BA_K_DONE : s->claim);
#else
        *reply = TF_STATUS(s->stage, s->claim);
#endif
        return BA_ACCEPT;
    default:
        *reply = TF_REPLY_REFUSED;
        return BA_REJECT;
    }
}

ba_verdict tf_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    const tf_state *s = state;
    tf_state *n = next;
    *n = *s;
    ba_verdict v = BA_ACCEPT;
    if (in->kind == BA_IN_REQUEST) v = request(s, n, in, ctx, out, reply);
    else completion(s, n, in, ctx, out);
    n->claim = tf_claim(n); /* state that others read: derived from the outcomes by the algebra only */
    return v;
}

void tf_initial(void *state)
{
    tf_state *s = state;
    tf_state zero = {TF_IDLE, 0, {BA_YES, BA_NO}, 0, {{BA_NO, BA_NO, 0}, {BA_NO, BA_NO, 0}}};
    *s = zero;
}
