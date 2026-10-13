package io.github.sparkcyf.voicesipagent.telephony

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AudioWorkerTest {
    @Test fun idleAudioOpenerReleasesItsThreadAndRestartsForTheNextCall() {
        fun runOpen(): Thread {
            val done = CountDownLatch(1)
            val thread = AtomicReference<Thread>()
            CellularLeg.audioExecutor.execute { thread.set(Thread.currentThread()); done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            return thread.get()
        }
        val first = runOpen()
        first.join(2000)
        assertFalse(first.isAlive)
        val second = runOpen()
        second.join(2000)
        assertFalse(second.isAlive)
        assertNotSame(first, second)
    }
}
