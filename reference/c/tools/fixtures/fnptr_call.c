/* Negative control for check_purity.sh: calls a routine at a fixed flash address (a HAL function, a boot ROM) through a function-pointer typedef. */
#include "ballarch.h"

typedef void (*hal_fn)(uint32_t);

ba_verdict fp_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict fp_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)ctx;
    (void)next;
    (void)out;
    ((hal_fn)0x08001235u)((uint32_t)in->arg); /* a dose sent outside the ledger; no symbol, no "( type * )" */
    *reply = 0;
    return BA_ACCEPT;
}
