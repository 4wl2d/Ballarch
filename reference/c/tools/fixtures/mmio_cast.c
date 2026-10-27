/* Negative control for check_purity.sh: a register store without volatile, through an integer cast to a pointer. */
#include "ballarch.h"

ba_verdict cast_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict cast_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)ctx;
    (void)next;
    (void)out;
    *(uint32_t *)0x40020018u = (uint32_t)in->arg; /* no volatile: no symbol, and no volatile to find */
    *reply = 0;
    return BA_ACCEPT;
}
