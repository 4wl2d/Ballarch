/*
 * Positive control for check_purity.sh: pure code that compilers translate with
 * read-only tables, struct copies, a local array (which a compiler that protects
 * the stack by default instruments with __stack_chk_fail) and, on small cores,
 * runtime helpers for division and 64-bit arithmetic. It must pass, on the host
 * and for a Cortex-M0 (no divide instruction).
 */
#include "ballarch.h"

typedef struct {
    uint8_t level;
    uint32_t total;
} pd_state;

static const uint16_t STEPS[8] = {1, 2, 4, 8, 16, 32, 64, 128};
static const char *const LABELS[3] = {"low", "mid", "high"}; /* pointers: .data.rel.ro under PIE */

ba_verdict pd_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict pd_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    const pd_state *s = state;
    pd_state *n = next;
    *n = *s;
    (void)out;
    int32_t divisor = in->request == 0 ? 1 : in->request;
    n->level = (uint8_t)(in->arg / divisor % 8);
    n->total = s->total + STEPS[n->level] + (uint32_t)(ctx->now_ms / 1000u);
    int32_t window[8];
    for (int i = 0; i < 8; i++) window[i] = in->arg * i;
    int32_t sum = 0;
    for (int i = 0; i < 8; i += (int)(in->arg & 3) + 1) sum += window[i];
    *reply = LABELS[n->level % 3][0] + (int32_t)sizeof(int *) + sum;
    return BA_ACCEPT;
}
