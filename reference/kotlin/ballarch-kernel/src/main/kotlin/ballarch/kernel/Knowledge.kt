package ballarch.kernel

/**
 * A truth value of Kleene's strong three-valued logic: [Yes], [No], or
 * [Maybe] when the available evidence does not decide.
 *
 * The connectives are the most informative ones that never contradict a
 * refinement: replacing a [Maybe] operand by [Yes] or [No] never turns a
 * definite result into its opposite. This monotonicity is what makes
 * [Knowledge] compose soundly.
 */
public enum class Tri {
    Yes,
    No,
    Maybe,
    ;

    public infix fun and(other: Tri): Tri = when {
        this == No || other == No -> No
        this == Yes && other == Yes -> Yes
        else -> Maybe
    }

    public infix fun or(other: Tri): Tri = when {
        this == Yes || other == Yes -> Yes
        this == No && other == No -> No
        else -> Maybe
    }

    public operator fun not(): Tri = when (this) {
        Yes -> No
        No -> Yes
        Maybe -> Maybe
    }

    /** Whether [actual] is consistent with this value. [Maybe] admits both. */
    public fun admits(actual: Boolean): Boolean = this == Maybe || (this == Yes) == actual

    public companion object {
        public fun of(value: Boolean): Tri = if (value) Yes else No
    }
}

/**
 * What is known about the effects of an operation made of constituent
 * effects: the calls a Ball made for it, or nested composite operations.
 *
 * - [all]: did every constituent take effect (net of compensation)?
 * - [any]: did at least one?
 *
 * The knowledge of one call follows from its [Outcome]: `Done` is
 * [Knowledge.Done], `NotDone` is [Knowledge.NotDone], `Unknown` is
 * [Knowledge.Unknown]. Constituents combine with [plus]; [NoEffects], the
 * knowledge of an operation with no constituents, is its identity.
 *
 * A claim about an operation is a claim about its final effect (rule R14). A
 * constituent counts as [Tri.No] only once it completed `NotDone` (or with an
 * answer meaning the effect did not happen), or once the Ball can no longer make
 * it for this operation. A call that is open, committed but not yet sent, or
 * that the Ball may still make counts as [Tri.Maybe], and so does the undoing of
 * a constituent the Ball may still compensate.
 *
 * **Composition closure.** If every constituent's knowledge is honest (it
 * admits what really happened), the combined knowledge is honest too, and each
 * of its two coordinates is the most informative honest value. Honest
 * constituents need honest adapters (R7) and absorbing compensations. A Ball
 * that derives every claim about a composite operation from this algebra,
 * counting every call it may still make as [Tri.Maybe], makes through its port
 * only claims that admit the final truth. If it answers the request for the
 * operation once the operation is settled, a caller one level up enters that
 * reply as one summand of its own knowledge ([reportedKnowledge]); since [plus]
 * is associative, the result equals the knowledge of the flattened operation.
 * The kernel tests check soundness and exactness exhaustively for up to four
 * constituents, and that a settled composite entered one level up equals the
 * flattened knowledge (associativity).
 */
public class Knowledge private constructor(public val all: Tri, public val any: Tri) {
    /** Combine with an independent constituent (or composite) of the same operation. */
    public operator fun plus(other: Knowledge): Knowledge = of(all and other.all, any or other.any)

    /** Every constituent took effect. */
    public val isDone: Boolean get() = all == Tri.Yes && any == Tri.Yes

    /** No constituent took effect. Also true for [NoEffects]. */
    public val isNotDone: Boolean get() = any == Tri.No

    /** Some constituents took effect and others did not: a definite partial effect. */
    public val isPartial: Boolean get() = all == Tri.No && any == Tri.Yes

    /** Neither "all" nor "none" is established. */
    public val isUncertain: Boolean get() = all == Tri.Maybe || any == Tri.Maybe

    /** Whether a ground truth (every constituent applied / at least one applied) is consistent with this knowledge. */
    public fun admits(allApplied: Boolean, anyApplied: Boolean): Boolean = all.admits(allApplied) && any.admits(anyApplied)

    /**
     * The honest three-valued outcome of this composite seen as one call:
     * `Done` only if every constituent took effect, `NotDone` only if none did
     * (including [NoEffects]), otherwise `Unknown`. The projection is sound but
     * not exact: [Partial], [NotAllDone] and [SomeDone] all become `Unknown`. A
     * composite that can end that way should report this knowledge itself.
     */
    public fun <R> toOutcome(value: R, notDone: NotDoneReason<R>, unknown: UnknownReason): Outcome<R> = when {
        isDone -> Outcome.Done(value)
        isNotDone -> Outcome.NotDone(notDone)
        else -> Outcome.Unknown(unknown)
    }

    override fun equals(other: Any?): Boolean = other is Knowledge && other.all == all && other.any == any

    override fun hashCode(): Int = all.hashCode() * 31 + any.hashCode()

    override fun toString(): String = name

    /** The name of this value: one of the seven constants below. */
    public val name: String
        get() = when (this) {
            NoEffects -> "NoEffects"
            Done -> "Done"
            NotDone -> "NotDone"
            Partial -> "Partial"
            NotAllDone -> "NotAllDone"
            SomeDone -> "SomeDone"
            else -> "Unknown"
        }

    public companion object {
        /** No constituents (all vacuously true, any false): the identity of [plus]. */
        public val NoEffects: Knowledge = Knowledge(Tri.Yes, Tri.No)

        /** Every constituent took effect. */
        public val Done: Knowledge = Knowledge(Tri.Yes, Tri.Yes)

        /** No constituent took effect. */
        public val NotDone: Knowledge = Knowledge(Tri.No, Tri.No)

        /** Some constituents took effect, and some did not. */
        public val Partial: Knowledge = Knowledge(Tri.No, Tri.Yes)

        /** Not every constituent took effect; whether any did is unknown. */
        public val NotAllDone: Knowledge = Knowledge(Tri.No, Tri.Maybe)

        /** At least one constituent took effect; whether all did is unknown. */
        public val SomeDone: Knowledge = Knowledge(Tri.Maybe, Tri.Yes)

        /** Nothing is established. */
        public val Unknown: Knowledge = Knowledge(Tri.Maybe, Tri.Maybe)

        /** The seven values the algebra can produce. */
        public val values: List<Knowledge> = listOf(NoEffects, Done, NotDone, Partial, NotAllDone, SomeDone, Unknown)

        /**
         * The knowledge with the given coordinates. Pairs that no operation can
         * produce, (Yes, Maybe) and (Maybe, No), are rejected.
         */
        public fun of(all: Tri, any: Tri): Knowledge {
            require(!(all == Tri.Yes && any == Tri.Maybe) && !(all == Tri.Maybe && any == Tri.No)) {
                "no operation has knowledge (all=$all, any=$any)"
            }
            return when (all) {
                Tri.Yes -> if (any == Tri.No) NoEffects else Done
                Tri.No -> when (any) {
                    Tri.No -> NotDone
                    Tri.Yes -> Partial
                    Tri.Maybe -> NotAllDone
                }
                Tri.Maybe -> if (any == Tri.Yes) SomeDone else Unknown
            }
        }

        /** The knowledge of one constituent whose net effect is [applied]. */
        public fun single(applied: Tri): Knowledge = of(applied, applied)
    }
}

/**
 * Whether the effect this call requested took place: `NotDone` is [Tri.No],
 * `Unknown` is [Tri.Maybe], and `Done(value)` is [effective] of the value.
 *
 * `Done` means that the destination processed the request; its value may
 * still describe a business refusal (a declined charge). Callers must pass
 * [effective] to say which answers mean the requested effect happened; only a
 * port whose sole answer is `Unit` has a predicate-free overload. A committed
 * call whose completion has not arrived is [Tri.Maybe] (rule R14), whether or
 * not the runtime has sent it.
 */
public fun <R> Outcome<R>.effect(effective: (R) -> Boolean): Tri = when (this) {
    is Outcome.Done -> Tri.of(effective(value))
    is Outcome.NotDone -> Tri.No
    is Outcome.Unknown -> Tri.Maybe
}

/** [effect] for a port whose only answer is `Unit`: `Done` can only mean the effect happened. */
public fun Outcome<Unit>.effect(): Tri = effect { true }

/** What this outcome establishes about the effect of its call (see [effect]). */
public fun <R> Outcome<R>.knowledge(effective: (R) -> Boolean): Knowledge = Knowledge.single(effect(effective))

/** [knowledge] for a port whose only answer is `Unit`. */
public fun Outcome<Unit>.knowledge(): Knowledge = knowledge { true }

/**
 * What a call to a composite tells its caller when the composite's reply reports
 * its own knowledge (Core §7.4). [settled] returns that knowledge only if the
 * reply describes the composite once no call is open and none is still to be
 * made; otherwise it returns `null`. An interim reply may be overtaken by later
 * completions, so it counts as [Knowledge.Unknown], and so does an `Unknown`
 * outcome. The result enters the caller's knowledge as one summand of
 * [Knowledge.plus].
 *
 * A `NotDone` request was never accepted: the composite refused it, or the
 * runtime never handed it over (its deadline passed in the inbox, the inbox was
 * full, or the instance is quarantined). Pass [onlyRequest] `true` only if no
 * other request, from this caller or any other, could start the same operation;
 * then the composite made no call for it, and `NotDone` is [Knowledge.NotDone].
 * Otherwise another request may have started the operation, a `NotDone` says
 * nothing about its effects, and it counts as [Knowledge.Unknown]. Two replies
 * about the same operation are never added with [Knowledge.plus]: the caller
 * keeps one of them, a settled one if it has one.
 *
 * A Boolean reading of such a reply (`knowledge { it.knowledge.isDone }`) would
 * turn a settled `Unknown` or `Partial` composite into a false `NotDone`.
 */
public fun <R> Outcome<R>.reportedKnowledge(onlyRequest: Boolean, settled: (R) -> Knowledge?): Knowledge = when (this) {
    is Outcome.Done -> settled(value) ?: Knowledge.Unknown
    is Outcome.NotDone -> if (onlyRequest) Knowledge.NotDone else Knowledge.Unknown
    is Outcome.Unknown -> Knowledge.Unknown
}

/**
 * The net effect of this call after compensating calls whose outcomes are
 * [undos], in any number: `effect ∧ ¬(u1 ∨ … ∨ uk)`. With no compensation it is
 * the call's own effect.
 *
 * Only *absorbing* compensations may be used here: the effect of one must mean
 * that no effect of the original request exists or can still take effect, for
 * example because the destination fences the request's reference, or because
 * the original request's delivery horizon has passed. Then one compensation
 * that took effect turns an `Unknown` call into a definite `NotDone`. A
 * compensation the Ball may still make must be passed in [undos] as an
 * `Outcome.Unknown`, so that it counts as `Maybe` (rule R14); leaving it out
 * gives a snapshot, which must not be offered as the operation's outcome.
 */
public fun <R, U> Outcome<R>.compensatedBy(
    undos: List<Outcome<U>>,
    effective: (R) -> Boolean,
    undone: (U) -> Boolean,
): Knowledge = Knowledge.single(effect(effective) and !undos.fold(Tri.No) { u, o -> u or o.effect(undone) })

/** [compensatedBy] with at most one compensating call, [undo] (`null` when there was none). */
public fun <R, U> Outcome<R>.compensatedBy(
    undo: Outcome<U>?,
    effective: (R) -> Boolean,
    undone: (U) -> Boolean,
): Knowledge = compensatedBy(listOfNotNull(undo), effective, undone)

/** [compensatedBy] for a `Unit` request and `Unit` compensations. */
public fun Outcome<Unit>.compensatedBy(undos: List<Outcome<Unit>>): Knowledge = compensatedBy(undos, { true }, { true })

/** [compensatedBy] for a `Unit` request and at most one `Unit` compensation. */
public fun Outcome<Unit>.compensatedBy(undo: Outcome<Unit>?): Knowledge = compensatedBy(listOfNotNull(undo))

/** The combined knowledge of independent constituents. */
public fun Iterable<Knowledge>.combine(): Knowledge = fold(Knowledge.NoEffects, Knowledge::plus)
