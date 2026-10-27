/* Negative control for check_purity.sh: a register address built as uintptr_t and turned into a pointer by memcpy, without a cast. */
#include "ballarch.h"

#include <string.h>

ba_verdict uptr_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict uptr_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)ctx;
    (void)next;
    (void)out;
    uintptr_t address = 0x40020018u;
    uint32_t *reg;
    memcpy(&reg, &address, sizeof reg);
    *reg = (uint32_t)in->arg;
    *reply = 0;
    return BA_ACCEPT;
}
