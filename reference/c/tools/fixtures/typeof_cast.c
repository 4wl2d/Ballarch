/* Negative control for check_purity.sh: reads the SysTick counter (a clock) through a cast spelled with __typeof__. */
#include "ballarch.h"

ba_verdict to_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict to_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)in;
    (void)ctx;
    (void)next;
    (void)out;
    *reply = *(__typeof__(reply))0xE000E018u; /* SysTick current value */
    return BA_ACCEPT;
}
