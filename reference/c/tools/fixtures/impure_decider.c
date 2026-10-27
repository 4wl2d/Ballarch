/* Negative control for check_purity.sh: reads the clock and keeps a hidden counter. */
#include "ballarch.h"

#include <time.h>

static int decisions; /* hidden mutable state: violates R1 */

ba_verdict impure_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict impure_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)in;
    (void)ctx;
    (void)next;
    (void)out;
    decisions++;
    *reply = (int32_t)time(NULL) + decisions; /* ambient clock: violates R2 */
    return BA_ACCEPT;
}
