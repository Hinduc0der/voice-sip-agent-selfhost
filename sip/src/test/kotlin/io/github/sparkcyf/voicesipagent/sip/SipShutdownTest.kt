package io.github.sparkcyf.voicesipagent.sip

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SipShutdownTest {
    @Test fun unregisterTimeoutStillClosesSocketAndCancelsLongTransactionTimers() {
        DatagramSocket(0).use { peer ->
            val log = object : SipLog {
                override fun info(msg: String) {}
                override fun warn(msg: String, t: Throwable?) {}
            }
            val ua = SipUserAgent(SipConfig(SipAccount("127.0.0.1", peer.localPort, "test", "test"), localPort = 0), log)
            val ready = CountDownLatch(1)
            val thread = AtomicReference<Thread>()
            ua.addStatusListener { _, status ->
                if (status.detail == "registering") { thread.set(Thread.currentThread()); ready.countDown() }
            }
            try {
                ua.start()
                assertTrue(ready.await(2, TimeUnit.SECONDS))
                val port = ua.transport.localPort
                val lateTask = AtomicInteger()
                ua.schedule(60_000) { lateTask.incrementAndGet() }
                // The local peer deliberately never answers either REGISTER.
                ua.stop(timeoutMs = 50)
                thread.get().join(2000)
                assertFalse(thread.get().isAlive)
                assertFalse(ua.status.ready)
                assertEquals("stopped", ua.status.detail)
                assertEquals(0, lateTask.get())
                DatagramSocket(port).use { assertEquals(port, it.localPort) }
            } finally {
                if (thread.get()?.isAlive == true) ua.stop(timeoutMs = 50)
            }
        }
    }
}
