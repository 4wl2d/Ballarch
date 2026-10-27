/*
 * Negative control for check_purity.sh: steps back from the state pointer to the
 * runtime, without a single cast, and transmits a dose outside the ledger. Only
 * the runtime-header check catches it. (The runtime passes a copy of the state,
 * so this would not find the runtime anyway.)
 */
#include "ballarch_runtime.h"

ba_verdict bd_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict bd_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)in;
    (void)ctx;
    (void)next;
    (void)out;
    const char *bytes = state;
    const void *base = bytes - offsetof(ba_runtime, rec) - offsetof(ba_record, state);
    const ba_runtime *rt = base;
    rt->pf->transmit(rt->pf->user, 1, 99u, 1, 1);
    *reply = 0;
    return BA_ACCEPT;
}
