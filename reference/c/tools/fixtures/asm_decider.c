/* Negative control for check_purity.sh: inline assembly (a special instruction, a cycle counter, a barrier). */
#include "ballarch.h"

ba_verdict asm_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict asm_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)in;
    (void)ctx;
    (void)next;
    (void)out;
    __asm__("nop");
    *reply = 0;
    return BA_ACCEPT;
}
