package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GatewayRuntimeExecutorTest {
    @Test fun cancellingPollingDrainsQueuedControlsReleasesThreadAndAllowsRestart() {
        val worker = GatewayRuntime.newWorker()
        try {
            val polls = AtomicInteger()
            val timer = worker.scheduleWithFixedDelay({ polls.incrementAndGet() }, 1, 1, TimeUnit.DAYS)
            val completed = CountDownLatch(1)
            val first = AtomicReference<Thread>()
            worker.execute {
                first.set(Thread.currentThread())
                timer.cancel(false)
                worker.execute { completed.countDown() }
            }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            first.get().join(3000)
            assertFalse(first.get().isAlive)
            assertEquals(0, polls.get())
            val second = AtomicReference<Thread>()
            val resumed = CountDownLatch(1)
            worker.execute { second.set(Thread.currentThread()); resumed.countDown() }
            assertTrue(resumed.await(2, TimeUnit.SECONDS))
            second.get().join(3000)
            assertFalse(second.get().isAlive)
            assertNotSame(first.get(), second.get())
        } finally { worker.shutdownNow() }
    }
}
