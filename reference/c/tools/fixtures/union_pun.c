/* Negative control for check_purity.sh: an integer becomes a pointer through a union, with no cast. */
#include "ballarch.h"

ba_verdict up_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict up_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)ctx;
    (void)next;
    (void)out;
    union {
        uint32_t address;
        uint32_t *word;
    } reg = {0x40020018u};
    *reg.word = (uint32_t)in->arg;
    *reply = 0;
    return BA_ACCEPT;
}
