/*
 * Negative control for check_purity.sh: a GNU line marker claims that the next lines are the
 * compiler's stdint.h, a system header, so the source checks would skip the register store,
 * and so would clang's AST. Only the scan for line markers in the decider's own files sees it.
 */
#include "ballarch.h"

ba_verdict lm_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict lm_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)ctx;
    (void)next;
    (void)out;
# 1 "/usr/include/stdint.h" 1 3
    *(volatile uint32_t *)0x40020018u = (uint32_t)in->arg;
# 18 "tools/fixtures/line_marker.c" 2
    *reply = 0;
    return BA_ACCEPT;
}
