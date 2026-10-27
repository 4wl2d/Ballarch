/* Negative control for check_purity.sh: a writable counter in a section named like relocated const data. */
#include "ballarch.h"

__attribute__((section(".data.rel.ro.counter"))) static uint32_t decisions;

ba_verdict rr_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict rr_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)in;
    (void)ctx;
    (void)next;
    (void)out;
    *reply = (int32_t)++decisions;
    return BA_ACCEPT;
}
