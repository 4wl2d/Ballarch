package ballarch.testkit

import ballarch.runtime.Cancellable
import ballarch.runtime.Scheduler
import java.util.PriorityQueue

/**
 * A deterministic discrete-event [Scheduler] with virtual time. Tasks run in
 * (time, submission order). Nothing runs until [runUntilIdle] or [step] is called.
 */
public class SimScheduler(start: Long = 0L) : Scheduler {
    private class Task(val time: Long, val seq: Long, val body: () -> Unit) {
        var cancelled = false
    }

    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.time }, { it.seq }))
    private var seq = 0L
    private var clock = start

    /** Hooks run before each task; used to inject crashes at chosen points. */
    public var beforeTask: (() -> Unit)? = null

    override fun now(): Long = clock

    override fun execute(task: () -> Unit) {
        add(clock, task)
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val t = add(clock + delayMillis.coerceAtLeast(0), task)
        return Cancellable { t.cancelled = true }
    }

    private fun add(time: Long, body: () -> Unit): Task = Task(time, seq++, body).also { queue.add(it) }

    public val pending: Int get() = queue.count { !it.cancelled }

    /** Number of tasks executed so far. */
    public var executed: Long = 0
        private set

    /** Runs the next task, advancing virtual time. Returns false when nothing is left. */
    public fun step(): Boolean {
        while (true) {
            val t = queue.poll() ?: return false
            if (t.cancelled) continue
            clock = maxOf(clock, t.time)
            beforeTask?.invoke()
            executed++
            t.body()
            return true
        }
    }

    /** Runs tasks until none are left or virtual time would pass [until]. Returns tasks run. */
    public fun runUntilIdle(until: Long = Long.MAX_VALUE, maxSteps: Int = 1_000_000): Int {
        var n = 0
        while (n < maxSteps) {
            val next = queue.peek() ?: break
            if (next.cancelled) {
                queue.poll()
                continue
            }
            if (next.time > until) break
            step()
            n++
        }
        check(n < maxSteps) { "simulation did not become idle within $maxSteps steps (livelock?)" }
        if (until != Long.MAX_VALUE && clock < until) clock = until
        return n
    }

    /** Advances virtual time by [millis], running every task that becomes due. */
    public fun advance(millis: Long): Int = runUntilIdle(until = clock + millis)
}
