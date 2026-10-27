/* Negative control for check_purity.sh: an integer converted to a pointer without a cast. */
#include "ballarch.h"

ba_verdict conv_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict conv_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)ctx;
    (void)next;
    (void)out;
    uint32_t *reg = 0x40020018u; /* refused by -Werror=int-conversion */
    *reg = (uint32_t)in->arg;
    *reply = 0;
    return BA_ACCEPT;
}
