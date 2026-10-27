/* Negative control for check_purity.sh: a store into backup SRAM (it survives a reset), state outside the committed record, through a pointer typedef. */
#include "ballarch.h"

typedef uint32_t *word_ptr;

ba_verdict td_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict td_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)ctx;
    (void)next;
    (void)out;
    word_ptr backup = (word_ptr)0x40024000u; /* STM32F4 backup SRAM */
    *backup += (uint32_t)in->arg;
    *reply = (int32_t)*backup;
    return BA_ACCEPT;
}
