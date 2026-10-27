package ballarch.testkit

import kotlin.random.Random

/**
 * Source of environmental nondeterminism: message fates, delays, crash points.
 * The system under test never consults it; only simulated environments do.
 */
public interface Chooser {
    /** Returns a value in `0 until n`. [label] documents the choice in counterexamples. */
    public fun choose(n: Int, label: String): Int

    /** The choices made in the current run, as `label=value` pairs. */
    public val history: List<String>
}

/** Pseudo-random choices from a seed; reproducible. */
public class RandomChooser(public val seed: Long) : Chooser {
    private val random = Random(seed)
    private val made = ArrayList<String>()

    override fun choose(n: Int, label: String): Int {
        require(n > 0)
        val v = if (n == 1) 0 else random.nextInt(n)
        made += "$label=$v"
        return v
    }

    override val history: List<String> get() = made.toList()
}

/**
 * Systematic enumeration of every choice sequence, in the style of stateless
 * model checking (VeriSoft): each run replays a prefix and the next run moves
 * the last non-exhausted choice forward. After [maxChoices] choice points in a
 * run, further choices take value 0, which keeps each run finite.
 */
public class ExhaustiveChooser(private val maxChoices: Int = 16) : Chooser {
    private val prefix = ArrayList<Int>()
    private val arity = ArrayList<Int>()
    private val labels = ArrayList<String>()
    private var position = 0
    private var exhausted = false

    /** Number of runs started so far. */
    public var runs: Int = 0
        private set

    override fun choose(n: Int, label: String): Int {
        require(n > 0)
        if (position >= maxChoices) return 0
        val v: Int
        if (position < prefix.size) {
            v = prefix[position]
            check(arity[position] == n) {
                "nondeterministic replay at choice $position ($label): arity ${arity[position]} became $n"
            }
            labels[position] = label
        } else {
            v = 0
            prefix += 0
            arity += n
            labels += label
        }
        position++
        return v
    }

    override val history: List<String>
        get() = (0 until position.coerceAtMost(prefix.size)).map { "${labels[it]}=${prefix[it]}" }

    /** Prepares the next run. Returns false when every sequence has been explored. */
    public fun next(): Boolean {
        if (exhausted) return false
        runs++
        // Drop choices that the finished run did not reach.
        while (prefix.size > position) {
            prefix.removeAt(prefix.size - 1)
            arity.removeAt(arity.size - 1)
            labels.removeAt(labels.size - 1)
        }
        while (prefix.isNotEmpty() && prefix.last() + 1 >= arity.last()) {
            prefix.removeAt(prefix.size - 1)
            arity.removeAt(arity.size - 1)
            labels.removeAt(labels.size - 1)
        }
        position = 0
        if (prefix.isEmpty()) {
            exhausted = true
            return false
        }
        prefix[prefix.size - 1] = prefix.last() + 1
        return true
    }
}

/**
 * Runs [body] once for every choice sequence of [chooser] (at most [maxRuns]).
 * Returns the number of runs executed.
 */
public fun explore(chooser: ExhaustiveChooser = ExhaustiveChooser(), maxRuns: Int = 100_000, body: (Chooser) -> Unit): Int {
    var runs = 0
    do {
        body(chooser)
        runs++
        check(runs < maxRuns) { "exploration exceeded $maxRuns runs; tighten the scenario bounds" }
    } while (chooser.next())
    return runs
}
