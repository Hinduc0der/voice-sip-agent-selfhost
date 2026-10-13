package io.github.sparkcyf.voicesipagent.pjsua2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owns one native-library lifetime. Timers and queued work cannot cross a stop/restart. */
internal class PjsuaWorker(
    private val initialize: () -> Unit,
    private val poll: () -> Unit,
    private val release: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private class Session {
        val queue = LinkedBlockingQueue<() -> Unit>()
        val timers = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "pjsua-timer").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        @Volatile var stopping = false
        lateinit var thread: Thread
    }

    private val lock = Any()
    @Volatile private var session: Session? = null
    val running: Boolean get() = session?.let { !it.stopping } == true

    fun start() = synchronized(lock) {
        session?.let {
            if (it.thread.isAlive) {
                check(!it.stopping) { "pjsua2 is shutting down" }
                return@synchronized
            }
            session = null
        }
        val current = Session()
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        current.thread = Thread({
            try {
                try { initialize() } catch (t: Throwable) { failure = t; return@Thread }
                finally { ready.countDown() }
                while (true) {
                    while (true) {
                        val task = current.queue.poll() ?: break
                        try { task() } catch (t: Throwable) { onFailure(t) }
                    }
                    if (current.stopping && current.queue.isEmpty()) break
                    poll()
                }
            } catch (t: Throwable) { onFailure(t) }
            finally {
                current.stopping = true
                current.timers.shutdownNow()
                try { release() } catch (t: Throwable) { onFailure(t) }
            }
        }, "pjsua").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }
        session = current
        current.thread.start()
        ready.await()
        failure?.let {
            current.thread.join()
            session = null
            throw IllegalStateException("pjsua2 init failed: ${it.message}", it)
        }
    }

    private fun enqueue(current: Session, task: () -> Unit) {
        // Native callbacks may enqueue object deletion during shutdown. Drain those on their own
        // thread, while ignoring late external callbacks and timer work from the previous lifetime.
        synchronized(current) {
            if (session === current && (!current.stopping || Thread.currentThread() === current.thread)) current.queue.add(task)
        }
    }

    fun post(task: () -> Unit) { session?.let { enqueue(it, task) } }

    fun schedule(delay: Long, task: () -> Unit): ScheduledFuture<*> {
        val current = session ?: error("pjsua2 stopped")
        return current.timers.schedule({ enqueue(current, task) }, delay, TimeUnit.MILLISECONDS)
    }

    fun every(period: Long, task: () -> Unit): ScheduledFuture<*> {
        val current = session ?: error("pjsua2 stopped")
        return current.timers.scheduleWithFixedDelay({ enqueue(current, task) }, period, period, TimeUnit.MILLISECONDS)
    }

    /** Called after the endpoint has removed its account and calls; never on the pjsua thread. */
    fun stop() {
        val current = session ?: return
        check(Thread.currentThread() !== current.thread) { "cannot stop pjsua2 inside its callback" }
        synchronized(current) { current.stopping = true; current.timers.shutdownNow() }
        current.thread.join(5000)
        check(!current.thread.isAlive) { "pjsua2 shutdown did not complete" }
        check(current.timers.awaitTermination(1, TimeUnit.SECONDS)) { "pjsua2 timer shutdown did not complete" }
        synchronized(lock) { if (session === current) session = null }
    }
}
