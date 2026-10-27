/* Negative control for check_purity.sh: a libc function whose name starts like a compiler helper's (__get...). */
#include "ballarch.h"

int __gettimeofday(void *tv, void *tz);

ba_verdict hc_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict hc_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)in;
    (void)ctx;
    (void)out;
    *reply = __gettimeofday(next, 0); /* the wall clock lands in the next state */
    return BA_ACCEPT;
}
