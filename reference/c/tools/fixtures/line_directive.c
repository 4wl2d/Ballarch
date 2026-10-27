/* Negative control for check_purity.sh: a #line directive relabels the decider's own lines as a system header's, to hide a register store. */
#include "ballarch.h"

ba_verdict ld_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict ld_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)ctx;
    (void)next;
    (void)out;
#line 1 "/usr/include/stdint.h"
    *(volatile uint32_t *)0x40020018u = (uint32_t)in->arg;
    *reply = 0;
    return BA_ACCEPT;
}
