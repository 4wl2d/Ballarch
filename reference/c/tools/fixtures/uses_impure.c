/* Negative control for check_purity.sh --uses: a decider that is pure itself but calls an impure helper. */
#include "ballarch.h"

int helper_count(int x);
ba_verdict uses_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict uses_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)ctx;
    (void)next;
    (void)out;
    *reply = helper_count(in->arg);
    return BA_ACCEPT;
}
