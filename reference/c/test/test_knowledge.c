/*
 * Exhaustive check of the C knowledge algebra (Core §4.2) against truth tables
 * derived independently of it: every three-valued value stands for the set of
 * Boolean worlds it admits, and every expected result is the most informative
 * value that admits every world, computed by enumerating the worlds (as the
 * Kotlin KnowledgeTest does). Checked:
 *   1. not, and, or equal supervaluation over the worlds;
 *   2. ba_k_plus is a commutative monoid on the seven values, with NoEffects as identity;
 *   3. for up to four constituents, each Done, NotDone or Unknown, with no
 *      compensation or one absorbing compensation that completed Done, NotDone
 *      or Unknown: the combined knowledge admits every world consistent with the
 *      outcomes (soundness) and each coordinate is the most informative such
 *      value (exactness). The number of worlds must equal the Kotlin test's 69,905;
 *   4. the same for claims made before the operation ends (ba_claim): a call
 *      may also be open or still to be made, and a compensation may still be made;
 *   5. absorbing compensation, business refusals, the projection to one
 *      outcome, the names, and the two pairs no operation produces.
 * A failure prints a line starting with "counterexample"; the Makefile's
 * algebra mutants must produce one.
 */
#include "ballarch_knowledge.h"

#include <stdio.h>

static int failures;
static char first[200];

#define FAIL(...)                                                       \
    do {                                                                \
        if (failures++ == 0) snprintf(first, sizeof first, __VA_ARGS__); \
    } while (0)

static const ba_tri TRIS[3] = {BA_NO, BA_YES, BA_MAYBE};

/* The worlds (truth values) a three-valued value admits: bit 0 = false admitted, bit 1 = true admitted. */
static unsigned worlds(ba_tri t) { return t == BA_YES ? 2u : t == BA_NO ? 1u : 3u; }

/* The most informative value that admits exactly the worlds in the set (non-empty). */
static ba_tri supremum(unsigned set) { return set == 2u ? BA_YES : set == 1u ? BA_NO : BA_MAYBE; }

static const char *tri_name(ba_tri t) { return t == BA_YES ? "Yes" : t == BA_NO ? "No" : "Maybe"; }

static void connectives(void)
{
    for (int i = 0; i < 3; i++) {
        ba_tri a = TRIS[i];
        unsigned nots = 0;
        for (int x = 0; x < 2; x++)
            if (worlds(a) & (1u << x)) nots |= 1u << !x;
        if (ba_not(a) != supremum(nots)) FAIL("not %s is %s", tri_name(a), tri_name(ba_not(a)));
        for (int j = 0; j < 3; j++) {
            ba_tri b = TRIS[j];
            unsigned ands = 0, ors = 0;
            for (int x = 0; x < 2; x++)
                for (int y = 0; y < 2; y++)
                    if ((worlds(a) & (1u << x)) && (worlds(b) & (1u << y))) {
                        ands |= 1u << (x && y);
                        ors |= 1u << (x || y);
                    }
            if (ba_and(a, b) != supremum(ands)) FAIL("%s and %s is %s", tri_name(a), tri_name(b), tri_name(ba_and(a, b)));
            if (ba_or(a, b) != supremum(ors)) FAIL("%s or %s is %s", tri_name(a), tri_name(b), tri_name(ba_or(a, b)));
        }
    }
}

/* The named values of ballarch_knowledge.h, in the order of NAMES. */
static ba_knowledge seven(int i)
{
    switch (i) {
    case 0: return BA_K_NO_EFFECTS;
    case 1: return BA_K_DONE;
    case 2: return BA_K_NOT_DONE;
    case 3: return BA_K_PARTIAL;
    case 4: return BA_K_NOT_ALL_DONE;
    case 5: return BA_K_SOME_DONE;
    default: return BA_K_UNKNOWN;
    }
}
static const char *const NAMES[7] = {"NoEffects", "Done", "NotDone", "Partial", "NotAllDone", "SomeDone", "Unknown"};

static int is_one_of_seven(ba_knowledge k)
{
    for (int i = 0; i < 7; i++)
        if (ba_k_eq(k, seven(i))) return 1;
    return 0;
}

static void monoid(void)
{
    for (int i = 0; i < 7; i++) {
        ba_knowledge x = seven(i);
        if (!ba_k_eq(ba_k_plus(x, BA_K_NO_EFFECTS), x) || !ba_k_eq(ba_k_plus(BA_K_NO_EFFECTS, x), x))
            FAIL("NoEffects is not an identity for %s", NAMES[i]);
        for (int j = 0; j < 7; j++) {
            ba_knowledge y = seven(j);
            if (!is_one_of_seven(ba_k_plus(x, y))) FAIL("%s + %s is not one of the seven values", NAMES[i], NAMES[j]);
            if (!ba_k_eq(ba_k_plus(x, y), ba_k_plus(y, x))) FAIL("%s + %s is not commutative", NAMES[i], NAMES[j]);
            for (int k = 0; k < 7; k++) {
                ba_knowledge z = seven(k);
                if (!ba_k_eq(ba_k_plus(ba_k_plus(x, y), z), ba_k_plus(x, ba_k_plus(y, z))))
                    FAIL("(%s + %s) + %s is not associative", NAMES[i], NAMES[j], NAMES[k]);
            }
        }
    }
}

/*
 * One constituent: the outcome of its call (or open / still to be made) and of
 * its absorbing compensation (none, an outcome, and whether one may still be made).
 */
enum { C_DONE, C_NOT_DONE, C_UNKNOWN, C_OPEN };
enum { U_NONE, U_DONE, U_NOT_DONE, U_UNKNOWN };

typedef struct {
    int call, undo, undo_open;
} constituent;

static ba_kind call_kind(int c) { return c == C_DONE ? BA_DONE : c == C_NOT_DONE ? BA_NOT_DONE : BA_UNKNOWN; }
static ba_kind undo_kind(int u) { return u == U_DONE ? BA_DONE : u == U_NOT_DONE ? BA_NOT_DONE : BA_UNKNOWN; }

/* The worlds of a call's final effect: what its outcome admits; an open call may end either way. */
static unsigned effect_worlds(int call) { return call == C_OPEN ? 3u : call == C_DONE ? 2u : call == C_NOT_DONE ? 1u : 3u; }

/* The worlds of "undone" at the end: a completed compensation's effect, or anything if one may still be made. */
static unsigned undone_worlds(int undo, int undo_open)
{
    unsigned so_far = undo == U_NONE ? 1u : undo == U_DONE ? 2u : undo == U_NOT_DONE ? 1u : 3u;
    if (!undo_open) return so_far;
    unsigned later = 0;
    for (int u = 0; u < 2; u++)
        if (so_far & (1u << u)) later |= u ? 2u : 3u; /* undone stays undone; not yet undone may still be undone */
    return later;
}

/* The worlds of the constituent's final net effect: applied and not undone. */
static unsigned net_worlds(const constituent *c)
{
    unsigned nets = 0;
    for (int e = 0; e < 2; e++)
        for (int u = 0; u < 2; u++)
            if ((effect_worlds(c->call) & (1u << e)) && (undone_worlds(c->undo, c->undo_open) & (1u << u))) nets |= 1u << (e && !u);
    return nets;
}

/* What the algebra derives for one constituent, through the public functions only. */
static ba_knowledge derived(const constituent *c, int as_claim)
{
    ba_tri undone = c->undo == U_NONE ? BA_NO : ba_effect_of(undo_kind(c->undo), 1);
    if (as_claim) {
        ba_tri effect = c->call == C_OPEN ? BA_NO : ba_effect_of(call_kind(c->call), 1);
        return ba_k_single(ba_claim(effect, c->call == C_OPEN, undone, c->undo_open));
    }
    ba_tri effect = ba_effect_of(call_kind(c->call), 1);
    return ba_k_single(c->undo == U_NONE ? effect : ba_net(effect, undone));
}

/*
 * Checks every sequence of up to four constituents drawn from `kinds`.
 * Returns the number of worlds checked.
 */
static long composition(const constituent *kinds, int n_kinds, int as_claim)
{
    long checked = 0;
    for (int n = 0; n <= 4; n++) {
        long sequences = 1;
        for (int i = 0; i < n; i++) sequences *= n_kinds;
        for (long code = 0; code < sequences; code++) {
            const constituent *parts[4];
            long c = code;
            ba_knowledge k = BA_K_NO_EFFECTS;
            for (int i = 0; i < n; i++) {
                parts[i] = &kinds[c % n_kinds];
                c /= n_kinds;
                k = ba_k_plus(k, derived(parts[i], as_claim));
            }
            /* Enumerate every combination of the constituents' possible net effects. */
            unsigned alls = 0, anys = 0;
            int choices[4] = {0, 0, 0, 0};
            for (;;) {
                int possible = 1, all = 1, any = 0;
                for (int i = 0; i < n; i++) {
                    if (!(net_worlds(parts[i]) & (1u << choices[i]))) possible = 0;
                    all = all && choices[i];
                    any = any || choices[i];
                }
                if (possible) {
                    checked++;
                    alls |= 1u << all;
                    anys |= 1u << any;
                    if (!ba_k_admits(k, all, any)) FAIL("%s with %d constituent(s) (sequence %ld) does not admit all=%d any=%d", ba_k_name(k), n, code, all, any);
                }
                int i = 0;
                while (i < n && choices[i] == 1) choices[i++] = 0;
                if (i == n) break;
                choices[i] = 1;
            }
            if (k.all != supremum(alls) || k.any != supremum(anys))
                FAIL("%s with %d constituent(s) (sequence %ld) is not exact: expected (%s, %s)", ba_k_name(k), n, code, tri_name(supremum(alls)),
                     tri_name(supremum(anys)));
        }
    }
    return checked;
}

static void specifics(void)
{
    if (!ba_k_eq(ba_k_single(ba_net(BA_MAYBE, BA_YES)), BA_K_NOT_DONE)) FAIL("Unknown compensated by Done is not NotDone");
    if (!ba_k_eq(ba_k_single(ba_net(BA_MAYBE, BA_NO)), BA_K_UNKNOWN)) FAIL("Unknown compensated by NotDone is not Unknown");
    if (!ba_k_eq(ba_k_single(ba_net(BA_MAYBE, BA_MAYBE)), BA_K_UNKNOWN)) FAIL("Unknown compensated by Unknown is not Unknown");
    if (ba_effect_of(BA_DONE, 0) != BA_NO) FAIL("a business refusal inside Done counts as an effect");
    if (!ba_k_eq(ba_k_plus(ba_k_single(ba_effect_of(BA_DONE, 1)), ba_k_single(ba_effect_of(BA_NOT_DONE, 0))), BA_K_PARTIAL))
        FAIL("Done + NotDone is not Partial");
    static const ba_kind OUTCOME[7] = {BA_NOT_DONE, BA_DONE, BA_NOT_DONE, BA_UNKNOWN, BA_UNKNOWN, BA_UNKNOWN, BA_UNKNOWN};
    int valid = 0;
    for (int i = 0; i < 7; i++) {
        if (ba_k_outcome(seven(i)) != OUTCOME[i]) FAIL("%s projects to the wrong outcome", NAMES[i]);
        const char *name = ba_k_name(seven(i));
        for (int j = 0; NAMES[i][j] || name[j]; j++)
            if (NAMES[i][j] != name[j]) {
                FAIL("the name of %s is %s", NAMES[i], name);
                break;
            }
    }
    for (int a = 0; a < 3; a++)
        for (int b = 0; b < 3; b++) valid += ba_k_valid(BA_KNOWLEDGE(TRIS[a], TRIS[b]));
    if (valid != 7 || ba_k_valid(BA_KNOWLEDGE(BA_YES, BA_MAYBE)) || ba_k_valid(BA_KNOWLEDGE(BA_MAYBE, BA_NO)))
        FAIL("ba_k_valid accepts %d pairs, or one that no operation produces", valid);
}

int main(void)
{
    connectives();
    monoid();
    /* As the Kotlin test: forward outcome x {no compensation, compensation Done/NotDone/Unknown}. */
    constituent outcomes[12];
    int n = 0;
    for (int c = C_DONE; c <= C_UNKNOWN; c++)
        for (int u = U_NONE; u <= U_UNKNOWN; u++) outcomes[n++] = (constituent){c, u, 0};
    long kotlin = composition(outcomes, n, 0);
    if (kotlin != 69905) FAIL("explored %ld worlds, not the Kotlin test's 69905", kotlin);
    /* Claims before the end: the call may be open, and a compensation may still be made. */
    constituent claims[32];
    n = 0;
    for (int c = C_DONE; c <= C_OPEN; c++)
        for (int u = U_NONE; u <= U_UNKNOWN; u++)
            for (int o = 0; o < 2; o++) claims[n++] = (constituent){c, u, o};
    long timed = composition(claims, n, 1);
    specifics();
    if (failures) {
        printf("counterexample in run 0 [algebra]: %s (%d checks failed)\n", first, failures);
        return 1;
    }
    printf("knowledge algebra: connectives, monoid laws and composition checked; %ld worlds for completed outcomes (as Kotlin), "
           "%ld worlds for claims made before the end; no violation\n",
           kotlin, timed);
    return 0;
}
