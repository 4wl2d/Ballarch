/*
 * Negative control for check_purity.sh: includes the compiler's intrinsics header and reads the
 * cycle counter through one of its inline functions. The builtin and the assembly are inside the
 * system header, so only the rule that a decider includes no hosted system header catches it.
 */
#include "ballarch.h"
#if defined(__x86_64__) || defined(__i386__)
#include <x86intrin.h>
#define CYCLES() __rdtsc()
#else
#include <time.h>
#define CYCLES() 0
#endif

ba_verdict ih_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict ih_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)in;
    (void)ctx;
    (void)next;
    (void)out;
    *reply = (int32_t)CYCLES();
    return BA_ACCEPT;
}
