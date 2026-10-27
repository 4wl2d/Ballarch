/*
 * Negative control for check_purity.sh: calls a boot-ROM routine through the ROM's table of
 * function pointers at a fixed address, as vendor ROM APIs are called. The pointer type is a
 * typedef after a struct body, so no "( type * )" and no pointer typedef shows in the source,
 * and the object has no symbol: only the clang AST check sees the cast and the indirect call.
 */
#include "ballarch.h"

typedef struct {
    void (*send_byte)(uint32_t byte);
} const *rom_api;

ba_verdict rom_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

ba_verdict rom_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply)
{
    (void)state;
    (void)ctx;
    (void)next;
    (void)out;
    ((rom_api)0x1FFF1FF0u)->send_byte((uint32_t)in->arg);
    *reply = 0;
    return BA_ACCEPT;
}
