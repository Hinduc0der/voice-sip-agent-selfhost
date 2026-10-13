package io.github.sparkcyf.voicesipagent.pjsua2

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PjsuaWorkerTest {
    @Test fun stoppingDrainsDeferredDeletesBeforeNativeDestructionAndRestartsOnANewThread() {
        val events = mutableListOf<String>()
        val thread = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        val finishTask = CountDownLatch(1)
        val worker = PjsuaWorker({ thread.set(Thread.currentThread()); events += "initialize" },
            { Thread.sleep(1) }, { events += "destroy" }, { throw AssertionError(it) })
        worker.start()
        val first = thread.get()
        worker.post {
            entered.countDown()
            assertTrue(finishTask.await(2, TimeUnit.SECONDS))
            events += "account cleanup"
            worker.post { events += "delete wrappers" }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val stopEntered = CountDownLatch(1)
        val stopper = Thread { stopEntered.countDown(); worker.stop() }.apply { start() }
        assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
        finishTask.countDown()
        stopper.join(3000)
        assertFalse(stopper.isAlive)
        assertFalse(first.isAlive)
        assertFalse(worker.running)
        assertEquals(listOf("initialize", "account cleanup", "delete wrappers", "destroy"), events)
        worker.start()
        assertNotSame(first, thread.get())
        worker.stop()
        worker.stop()
        assertEquals(2, events.count { it == "destroy" })
    }

    @Test fun timersAndLatePostsFromStoppedLibraryCannotReachTheNextLifetime() {
        val leaked = AtomicInteger()
        val worker = PjsuaWorker({}, { Thread.sleep(1) }, {}, { throw AssertionError(it) })
        worker.start()
        worker.schedule(100) { leaked.incrementAndGet() }
        worker.every(100) { leaked.incrementAndGet() }
        worker.stop()
        worker.post { leaked.incrementAndGet() }
        worker.start()
        val settled = CountDownLatch(1)
        worker.schedule(200) { settled.countDown() }
        assertTrue(settled.await(2, TimeUnit.SECONDS))
        worker.stop()
        assertEquals(0, leaked.get())
    }

    @Test fun failedInitializationIsReleasedAndDoesNotPreventRetry() {
        val attempts = AtomicInteger()
        val releases = AtomicInteger()
        val worker = PjsuaWorker({ if (attempts.incrementAndGet() == 1) error("native init failed") },
            { Thread.sleep(1) }, { releases.incrementAndGet() }, { throw AssertionError(it) })
        try { worker.start(); fail("initialization should fail") } catch (_: IllegalStateException) {}
        assertFalse(worker.running)
        assertEquals(1, releases.get())
        worker.start()
        worker.stop()
        assertEquals(2, releases.get())
    }
}
