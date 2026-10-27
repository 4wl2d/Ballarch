/*
 * A transfer controller: a composite operation with absorbing compensation,
 * and the knowledge algebra (rule R14) at the firmware level.
 *
 * A transfer between two tanks needs the source tank's outlet valve and the
 * destination tank's inlet valve open; one valve open alone spills. The
 * controller opens the source valve, then the destination valve, and closes
 * what it opened when it cannot have both. The valve controller's OPEN command
 * has no deduplication (NonIdempotent), and it may arrive late. CLOSE(token)
 * closes the valve that the OPEN with that token opened and fences the token:
 * an OPEN with a fenced token is refused, however late it arrives. CLOSE is
 * therefore an absorbing compensation, and repeating it changes nothing
 * (Idempotent).
 *
 * The controller's state carries its claim about the transfer: the knowledge
 * (all, any) of the two valves, combined by the algebra of ballarch_knowledge.h.
 * The claim is about the operation's final effect: a call that is open or that
 * the controller may still make counts as Maybe, and so does the undoing of a
 * valve the controller may still close.
 */
#ifndef TRANSFER_H
#define TRANSFER_H

#include "ballarch_knowledge.h"

enum { TF_PORT_OPEN = 1, TF_PORT_CLOSE = 2, TF_PORTS = 3 };
enum { TF_SOURCE = 0, TF_DESTINATION = 1 }; /* argument of OPEN */
enum { TF_OPENED = 1 };                      /* OPEN's answer when the valve opened */

enum { TF_REQ_START = 1, TF_REQ_STATUS = 2 };
enum { TF_REPLY_STARTED = 1, TF_REPLY_REFUSED = 2 };

typedef enum { TF_IDLE, TF_OPENING_SOURCE, TF_OPENING_DESTINATION, TF_CLOSING_DESTINATION, TF_CLOSING_SOURCE, TF_SETTLED } tf_stage;

#define TF_CALL_TIMEOUT_MS 300u
#define TF_MAX_CLOSES 2 /* CLOSE calls per valve; the runtime may also retry each one */

typedef struct {
    uint8_t effect;  /* ba_tri: what OPEN's completion established */
    uint8_t undone;  /* ba_tri: OR of the effects of the CLOSE calls so far */
    uint32_t token;  /* key of the OPEN call, which CLOSE fences */
} tf_valve;

typedef struct {
    uint8_t stage;      /* tf_stage */
    uint8_t closes;     /* CLOSE calls made in the current stage */
    ba_knowledge claim; /* the transfer's knowledge, as the controller claims it (R14) */
    uint32_t call;      /* key of the call the current stage waits for */
    tf_valve valve[2];
} tf_state;

BA_STATE_FITS(tf_state);

/* STATUS replies with stage << 8 | claim.all << 4 | claim.any. */
#define TF_STATUS(stage, k) ((int32_t)((stage) << 8 | (k).all << 4 | (k).any))

/* The Ball type, bound in transfer_ball.c. */
extern const ba_ball tf_ball;

/* The pure parts (transfer.c), exposed for unit tests. */
extern const ba_effect tf_effects[TF_PORTS];
void tf_initial(void *state);
ba_verdict tf_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);
ba_knowledge tf_claim(const tf_state *s);

#endif
