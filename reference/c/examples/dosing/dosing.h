/*
 * A therapy controller that drives an infusion pump over a serial link.
 *
 * Giving a dose twice can harm the patient, and the pump's command has no
 * idempotency key, so the dose port is NonIdempotent: the runtime never sends
 * a dose twice, and a dose whose fate is unknown (timeout, garbled answer,
 * power loss while it may have been on the wire) is reconciled by asking the
 * pump for its dose counter (a Safe port) after the link's delivery horizon,
 * never by dosing again.
 */
#ifndef DOSING_H
#define DOSING_H

#include "ballarch.h"

enum { TH_PORT_DOSE = 1, TH_PORT_COUNTER = 2, TH_PORTS = 3 };

enum { TH_REQ_START = 1, TH_REQ_STATUS = 2 };
enum { TH_REPLY_STARTED = 1, TH_REPLY_REFUSED = 2 };

typedef enum { TH_IDLE, TH_WAITING, TH_DOSING, TH_SETTLING, TH_QUERYING, TH_DONE, TH_ALARM } th_stage;

#define TH_MAX_DOSES 4
#define TH_INTERVAL_MS 1000u    /* between doses */
#define TH_RETRY_MS 200u        /* after a dose that provably was not given */
#ifdef TH_MUTANT_NO_HORIZON /* seeded bug: queries the pump before a late dose can arrive */
#define TH_HORIZON_MS 0u
#else
#define TH_HORIZON_MS 600u      /* after which an unanswered dose can no longer arrive */
#endif
#define TH_CALL_TIMEOUT_MS 300u
#define TH_MAX_RETRIES 2
#define TH_MAX_QUERIES 3

typedef struct {
    uint8_t stage;      /* th_stage */
    uint8_t prescribed; /* doses to give */
    uint8_t delivered;  /* doses known to be given */
    uint8_t retries;    /* consecutive doses that were provably not given */
    uint8_t queries;    /* counter queries in the current reconciliation */
    uint32_t call;      /* key of the call the current stage waits for */
} th_state;

BA_STATE_FITS(th_state);

/* The Ball type, bound in dosing_ball.c. */
extern const ba_ball th_ball;

/* The pure parts (dosing.c), exposed for unit tests. */
extern const ba_effect th_effects[TH_PORTS];
void th_initial(void *state);
ba_verdict th_step(const void *state, const ba_input *in, ba_ctx *ctx, void *next, ba_outputs *out, int32_t *reply);

#endif
