/* Negative control for check_purity.sh: reads the CPU's cycle counter through a compiler builtin (no symbol, no asm). */
#include "ballarch.h"

ba_verdict bc_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict bc_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)in;
    (void)ctx;
    (void)next;
    (void)out;
#if defined(__clang__)
    *reply = (int32_t)__builtin_readcyclecounter();
#elif defined(__x86_64__) || defined(__i386__)
    *reply = (int32_t)__builtin_ia32_rdtsc();
#else
    *reply = __builtin_return_address(0) != 0; /* who called: context outside the arguments */
#endif
    return BA_ACCEPT;
}
