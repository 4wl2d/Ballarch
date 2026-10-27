/*
 * The knowledge algebra of Core §4.2 (rule R14) in C11, for deciders.
 *
 * What a Ball may claim about an operation made of several calls: the pair
 * (all, any) of strong Kleene truth values over the constituents' net effects.
 * The functions are pure (tools/check_purity.sh checks ballarch_knowledge.c),
 * so a decider may call them. The reference is the Kotlin kernel's Knowledge.kt;
 * test/test_knowledge.c checks this implementation exhaustively against
 * truth tables computed by enumerating the possible worlds.
 *
 * Copyright (c) 2026 Vladislav Tomilov (4wl2d). See NOTICE.md at the
 * repository root: this code has no software license yet.
 */
#ifndef BALLARCH_KNOWLEDGE_H
#define BALLARCH_KNOWLEDGE_H

#include "ballarch.h"

/* A truth value of strong Kleene three-valued logic (K3). */
typedef enum { BA_NO = 0, BA_YES = 1, BA_MAYBE = 2 } ba_tri;

ba_tri ba_tri_of(int value);
ba_tri ba_and(ba_tri a, ba_tri b);
ba_tri ba_or(ba_tri a, ba_tri b);
ba_tri ba_not(ba_tri a);
int ba_tri_admits(ba_tri t, int actual); /* BA_MAYBE admits both */

/*
 * Knowledge of an operation: did every constituent take effect (all), did at
 * least one (any)? Each field holds a ba_tri in one byte, so the pair fits in
 * a Ball's state.
 */
typedef struct {
    uint8_t all, any;
} ba_knowledge;

#define BA_KNOWLEDGE(all_, any_) ((ba_knowledge){(uint8_t)(all_), (uint8_t)(any_)})
/* The seven values the algebra can produce (Core §4.2). */
#define BA_K_NO_EFFECTS BA_KNOWLEDGE(BA_YES, BA_NO)     /* no constituents: the identity of ba_k_plus */
#define BA_K_DONE BA_KNOWLEDGE(BA_YES, BA_YES)          /* every effect happened */
#define BA_K_NOT_DONE BA_KNOWLEDGE(BA_NO, BA_NO)        /* no effect happened */
#define BA_K_PARTIAL BA_KNOWLEDGE(BA_NO, BA_YES)        /* some happened, some did not */
#define BA_K_NOT_ALL_DONE BA_KNOWLEDGE(BA_NO, BA_MAYBE) /* not complete; whether anything happened is unknown */
#define BA_K_SOME_DONE BA_KNOWLEDGE(BA_MAYBE, BA_YES)   /* something happened; whether everything did is unknown */
#define BA_K_UNKNOWN BA_KNOWLEDGE(BA_MAYBE, BA_MAYBE)   /* nothing is established */

/* The knowledge of one constituent whose net effect is `net`. */
ba_knowledge ba_k_single(ba_tri net);
/* Combine independent constituents (or composites) of one operation: (all AND all, any OR any). */
ba_knowledge ba_k_plus(ba_knowledge x, ba_knowledge y);
/* Whether a ground truth (every constituent took effect / at least one did) is consistent with k. */
int ba_k_admits(ba_knowledge k, int all_applied, int any_applied);
int ba_k_eq(ba_knowledge x, ba_knowledge y);
/* One of the seven values; (Yes, Maybe) and (Maybe, No) and out-of-range bytes are not. */
int ba_k_valid(ba_knowledge k);
const char *ba_k_name(ba_knowledge k); /* "Done", ..., or "invalid" */
/* The honest three-valued outcome of the composite seen as one call: Done only if all, NotDone only if none. */
ba_kind ba_k_outcome(ba_knowledge k);

/*
 * Whether a call's requested effect took place, from its outcome: Done is
 * `effective` (Done may carry a business refusal), NotDone is No, Unknown is Maybe.
 */
ba_tri ba_effect_of(ba_kind kind, int effective);

/*
 * Net effect of a constituent after its compensations: effect AND NOT undone,
 * where undone is the OR of the effects of its compensation attempts. Only an
 * absorbing compensation may be used here: its effect must mean that no
 * effect of the original request exists or can still take effect.
 */
ba_tri ba_net(ba_tri effect, ba_tri undone);

/*
 * A constituent's net effect as a claim about the operation's final effect (R14):
 *   effect     what the call's completion established (ba_effect_of), or BA_NO
 *              if the Ball can no longer make the call for this operation;
 *   open       the call is open, or the Ball may still make it: its effect counts as Maybe;
 *   undone     the OR of the effects of its absorbing compensations so far;
 *   undo_open  a compensation is open or may still be made: undone counts as at least Maybe.
 */
ba_tri ba_claim(ba_tri effect, int open, ba_tri undone, int undo_open);

#endif
