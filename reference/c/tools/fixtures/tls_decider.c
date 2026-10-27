/* Negative control for check_purity.sh: a thread-local counter (.tbss), state no runtime commits. */
#include "ballarch.h"

static _Thread_local int tls_decisions;

ba_verdict tls_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict tls_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)in;
    (void)ctx;
    (void)next;
    (void)out;
    *reply = ++tls_decisions;
    return BA_ACCEPT;
}
