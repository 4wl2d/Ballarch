/* Negative control for check_purity.sh: register I/O and assembly in its own header, which calls itself a system header. */
#include "ballarch.h"
#include "hal.h"

ba_verdict sh_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict sh_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)ctx;
    (void)next;
    (void)out;
    led_on((uint32_t)in->arg);
    *reply = 0;
    return BA_ACCEPT;
}
