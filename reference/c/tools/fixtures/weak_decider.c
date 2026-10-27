/* Negative control for check_purity.sh: a weak global counter (nm type V), state no runtime commits. */
#include "ballarch.h"

__attribute__((weak)) int weak_decisions;

ba_verdict weak_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict weak_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)in;
    (void)ctx;
    (void)next;
    (void)out;
    *reply = ++weak_decisions;
    return BA_ACCEPT;
}
