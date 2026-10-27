/* Negative control for check_purity.sh: drives a GPIO pin through a memory-mapped register. */
#include "ballarch.h"

ba_verdict mmio_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict mmio_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)in;
    (void)ctx;
    (void)next;
    (void)out;
    *(volatile uint32_t *)0x40020018u = 1u << 5; /* GPIOA BSRR: I/O that leaves no symbol */
    *reply = 0;
    return BA_ACCEPT;
}
