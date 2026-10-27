package ballarch.runtime

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A handle to a scheduled task. */
public fun interface Cancellable {
    public fun cancel()
}

/**
 * The single logical thread on which a [Runtime] runs. All decider invocations,
 * commits and dispatch decisions happen in tasks submitted here, one at a time:
 * a task never starts while another runs, so steps never overlap or nest.
 * Adapters may complete on other threads; their callbacks are re-submitted here.
 */
public interface Scheduler {
    /** Current time in epoch milliseconds. Deciders see it only through the context. */
    public fun now(): Long

    /**
     * Run [task] on the loop, after the task that runs now, if any, and after
     * the tasks already submitted from the calling thread. Safe to call from any thread.
     */
    public fun execute(task: () -> Unit)

    /** Run [task] on the loop after [delayMillis]. Safe to call from any thread. */
    public fun schedule(delayMillis: Long, task: () -> Unit): Cancellable
}

/** A [Scheduler] backed by one dedicated thread and the system clock. */
public class ThreadScheduler(name: String = "ballarch-loop") : Scheduler, AutoCloseable {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    override fun now(): Long = System.currentTimeMillis()

    override fun execute(task: () -> Unit) {
        executor.execute(task)
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val future = executor.schedule(task, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }

    override fun close() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}

/**
 * A [Scheduler] whose loop is the thread that constructs it, such as an
 * application's main thread, so that a step needs no hand-off to another
 * thread. A task that thread submits while no task runs runs at once, inside
 * [execute]; a task it submits while one runs waits until that task and those
 * submitted before it are done. So a request submitted on the owning thread
 * is decided, committed and answered before [Runtime.request] returns, unless
 * its answer waits for an adapter, and a request that a watcher or an adapter
 * submits during a step is processed after that step.
 *
 * A task submitted from another thread, such as an adapter's report, is handed
 * to [post], which must run it later on the owning thread: for example
 * `Handler(Looper.getMainLooper())::post` on Android, or `SwingUtilities::invokeLater`.
 * A delayed task waits on [timer] and is then posted the same way.
 *
 * Every write of the store runs on the owning thread, so keep a store whose
 * writes block, such as a [FileStore], off a thread that must stay responsive.
 *
 * @param post runs a task later, on the owning thread; it may be called from any thread.
 * @param timer waits for delayed tasks; it never runs a task itself.
 * @param clock the current time in epoch milliseconds.
 */
public class InlineScheduler(
    private val post: (Runnable) -> Unit,
    private val timer: ScheduledExecutorService = defaultTimer,
    private val clock: () -> Long = System::currentTimeMillis,
) : Scheduler {
    private val owner: Thread = Thread.currentThread()
    private val queue = ArrayDeque<() -> Unit>()
    private var running = false

    override fun now(): Long = clock()

    override fun execute(task: () -> Unit) {
        if (Thread.currentThread() === owner) runOrQueue(task) else post(Runnable { runOrQueue(task) })
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val cancelled = AtomicBoolean(false)
        val due = timer.schedule({ post(Runnable { if (!cancelled.get()) runOrQueue(task) }) }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return Cancellable {
            cancelled.set(true)
            due.cancel(false)
        }
    }

    /** Queues [task] and, unless a task runs, runs the queue to its end. Owning thread only. */
    private fun runOrQueue(task: () -> Unit) {
        check(Thread.currentThread() === owner) {
            "InlineScheduler: a task ran on thread '${Thread.currentThread().name}'; post must run it on '${owner.name}'"
        }
        queue.addLast(task)
        if (running) return
        running = true
        try {
            while (true) (queue.removeFirstOrNull() ?: break)()
        } finally {
            running = false
            // A task threw: the tasks after it run in a later turn of the owning thread's loop.
            if (queue.isNotEmpty()) post(Runnable { runOrQueue {} })
        }
    }

    private companion object {
        /** The timer of every InlineScheduler constructed without one: a single daemon thread, started on first use. */
        val defaultTimer: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ballarch-inline-timer").apply { isDaemon = true } }
        }
    }
}
