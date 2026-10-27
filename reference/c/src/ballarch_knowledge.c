/*
 * The knowledge algebra of Core §4.2. Pure: no I/O, clock, globals or libc
 * calls; tools/check_purity.sh checks this file like a decider.
 *
 * Seeded bugs for the negative controls in the Makefile (never enabled in a build):
 *   BA_MUTANT_WEAK_AND            No AND Maybe is Maybe (weak Kleene): sound, but not exact
 *   BA_MUTANT_UNKNOWN_UNDO_COUNTS a compensation with an Unknown outcome counts as undoing
 *   BA_MUTANT_OPEN_AS_NO          a call that is open or may still be made counts as No
 */
#include "ballarch_knowledge.h"

ba_tri ba_tri_of(int value) { return value ? BA_YES : BA_NO; }

ba_tri ba_and(ba_tri a, ba_tri b)
{
#ifdef BA_MUTANT_WEAK_AND
    if (a == BA_MAYBE || b == BA_MAYBE) return BA_MAYBE;
#endif
    if (a == BA_NO || b == BA_NO) return BA_NO;
    if (a == BA_YES && b == BA_YES) return BA_YES;
    return BA_MAYBE;
}

ba_tri ba_or(ba_tri a, ba_tri b)
{
    if (a == BA_YES || b == BA_YES) return BA_YES;
    if (a == BA_NO && b == BA_NO) return BA_NO;
    return BA_MAYBE;
}

ba_tri ba_not(ba_tri a) { return a == BA_YES ? BA_NO : a == BA_NO ? BA_YES : BA_MAYBE; }

int ba_tri_admits(ba_tri t, int actual) { return t == BA_MAYBE || (t == BA_YES) == (actual != 0); }

ba_knowledge ba_k_single(ba_tri net) { return BA_KNOWLEDGE(net, net); }

ba_knowledge ba_k_plus(ba_knowledge x, ba_knowledge y)
{
    return BA_KNOWLEDGE(ba_and((ba_tri)x.all, (ba_tri)y.all), ba_or((ba_tri)x.any, (ba_tri)y.any));
}

int ba_k_admits(ba_knowledge k, int all_applied, int any_applied)
{
    return ba_tri_admits((ba_tri)k.all, all_applied) && ba_tri_admits((ba_tri)k.any, any_applied);
}

int ba_k_eq(ba_knowledge x, ba_knowledge y) { return x.all == y.all && x.any == y.any; }

int ba_k_valid(ba_knowledge k)
{
    if (k.all > BA_MAYBE || k.any > BA_MAYBE) return 0;
    return !(k.all == BA_YES && k.any == BA_MAYBE) && !(k.all == BA_MAYBE && k.any == BA_NO);
}

const char *ba_k_name(ba_knowledge k)
{
    if (!ba_k_valid(k)) return "invalid";
    if (k.all == BA_YES) return k.any == BA_NO ? "NoEffects" : "Done";
    if (k.all == BA_NO) return k.any == BA_NO ? "NotDone" : k.any == BA_YES ? "Partial" : "NotAllDone";
    return k.any == BA_YES ? "SomeDone" : "Unknown";
}

ba_kind ba_k_outcome(ba_knowledge k)
{
    if (k.all == BA_YES && k.any == BA_YES) return BA_DONE;
    if (k.any == BA_NO) return BA_NOT_DONE;
    return BA_UNKNOWN;
}

ba_tri ba_effect_of(ba_kind kind, int effective)
{
    if (kind == BA_DONE) return ba_tri_of(effective);
    if (kind == BA_NOT_DONE) return BA_NO;
    return BA_MAYBE;
}

ba_tri ba_net(ba_tri effect, ba_tri undone)
{
#ifdef BA_MUTANT_UNKNOWN_UNDO_COUNTS
    if (undone == BA_MAYBE) undone = BA_YES;
#endif
    return ba_and(effect, ba_not(undone));
}

ba_tri ba_claim(ba_tri effect, int open, ba_tri undone, int undo_open)
{
#ifdef BA_MUTANT_OPEN_AS_NO
    ba_tri e = open ? BA_NO : effect;
#else
    ba_tri e = open ? BA_MAYBE : effect;
#endif
    return ba_net(e, undo_open ? ba_or(undone, BA_MAYBE) : undone);
}
