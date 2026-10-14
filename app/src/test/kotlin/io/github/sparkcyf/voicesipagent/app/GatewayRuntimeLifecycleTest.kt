package io.github.sparkcyf.voicesipagent.app

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Process
import android.telecom.TelecomManager
import android.util.Log
import io.github.sparkcyf.voicesipagent.gateway.CallBridge
import io.github.sparkcyf.voicesipagent.pjsua2.PjsipConfig
import io.github.sparkcyf.voicesipagent.pjsua2.PjsipEndpoint
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.telephony.CallSnapshot
import io.github.sparkcyf.voicesipagent.telephony.CellularEndpoint
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import org.junit.Assert.*
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class GatewayRuntimeLifecycleTest {
    @Test fun switchOnCapturesDialerBeforeHelperConnects() {
        for (initialHolder in listOf("phone", null)) {
            Fixture(holder = initialHolder).use { f ->
                f.runtime.setTakeover(true)
                f.drain()
                assertTrue(f.runtime.health.value.dialerChangePending)
                assertFalse(f.runtime.health.value.dialerChangeFailed)
                verify(f.helper, never()).becomeDefaultDialer(anyString())
                f.connect()
                verify(f.helper).becomeDefaultDialer(initialHolder.orEmpty())
                assertEquals("gateway", f.holder)
                assertFalse(f.runtime.health.value.dialerChangePending)
            }
        }
    }

    @Test fun cancelledAcquisitionIsNotReplayedOnHelperConnection(): Unit = Fixture().use { f ->
        f.runtime.setTakeover(true)
        f.drain()
        f.runtime.cancelDialerChange()
        f.drain()
        f.connect()
        verify(f.helper, never()).becomeDefaultDialer(anyString())
    }

    @Test fun changedDialerWhileReconnectingIsLeftInPlace() {
        for (initialHolder in listOf("phone", null)) {
            Fixture(holder = initialHolder).use { f ->
                f.runtime.setTakeover(true)
                f.drain()
                assertTrue(f.runtime.health.value.dialerChangePending)
                f.holder = "chosen.phone"
                f.connect()
                verify(f.helper, never()).becomeDefaultDialer(anyString())
                assertFalse(f.runtime.health.value.dialerChangePending)
                assertEquals("chosen.phone", f.holder)
            }
        }
    }

    @Test fun switchingOffCancelsAcquisitionBeforeHelperConnects(): Unit = Fixture().use { f ->
        f.runtime.setTakeover(true)
        f.drain()
        f.runtime.setTakeover(false)
        f.drain()
        f.connect()
        verify(f.helper, never()).becomeDefaultDialer(anyString())
        assertFalse(f.runtime.health.value.dialerChangePending)
    }

    @Test fun rejectedForegroundLaunchAndQueuedControlsCannotStartResources(): Unit =
        Fixture(enabled = true, rejectLaunch = true).use { f ->
            f.runtime.restartSip()
            f.runtime.setLoopbackTest(true)
            f.runtime.applySettings(f.runtime.settings.copy(inboundTarget = "changed"))
            f.runtime.runSetup()
            f.drain()
            verify(f.context, atLeastOnce()).startForegroundService(any(Intent::class.java))
            verify(f.connector, never()).start()
            verify(f.network, never()).registerDefaultNetworkCallback(any(ConnectivityManager.NetworkCallback::class.java))
            assertTrue(f.endpoints.constructed().isEmpty())
            assertTrue(f.bridges.constructed().isEmpty())
            assertFalse(f.runtime.health.value.cellularReady)
        }

    @Test fun reminderOnlyPromotionDoesNotStartGateway(): Unit = Fixture(holder = "gateway").use { f ->
        f.promote()
        verify(f.connector, never()).start()
        assertTrue(f.endpoints.constructed().isEmpty())
        assertTrue(f.bridges.constructed().isEmpty())
    }

    @Test fun destructionBeforePromotionCancelsAcquisitionInsteadOfReplayingOnRestart(): Unit = Fixture().use { f ->
        f.runtime.setTakeover(true)
        f.runtime.onServiceCreated(f.serviceToken)
        f.drain()
        assertTrue(f.runtime.health.value.dialerChangePending)
        verify(f.connector, never()).start()
        assertTrue(f.endpoints.constructed().isEmpty())
        clearInvocations(f.context)

        // Promotion failed; destruction arrives without onForegroundStarted.
        f.runtime.onForegroundStopped(f.serviceToken)
        f.drain()
        assertFalse(f.runtime.health.value.dialerChangePending)
        verify(f.context, never()).startForegroundService(any(Intent::class.java))

        f.serviceToken = Any()
        f.connect()
        verify(f.helper, never()).becomeDefaultDialer(anyString())
        assertEquals("phone", f.holder)
    }

    @Test fun destructionContinuesCleanupAfterFailureWithoutRoleChangesOrAutomaticRestart(): Unit =
        Fixture(enabled = true).use { f ->
            f.connect()
            val endpoint = f.endpoints.constructed().single()
            val bridge = f.bridges.constructed().single()
            verify(endpoint).start()
            doThrow(IllegalStateException("bridge cleanup failed")).`when`(bridge).stop()
            clearInvocations(f.context)
            f.runtime.onForegroundStopped(f.serviceToken)
            f.runtime.onForegroundStopped(f.serviceToken)
            f.runtime.refreshCallAccess()
            f.runtime.restartSip()
            f.drain()
            verify(bridge).stop()
            verify(endpoint).stop(releaseTransport = true)
            verify(f.network).unregisterNetworkCallback(any(ConnectivityManager.NetworkCallback::class.java))
            verify(f.connector).stop()
            verify(f.helper, never()).becomeDefaultDialer(anyString())
            verify(f.helper, never()).restoreDefaultDialer(anyString())
            verify(f.context, never()).startForegroundService(any(Intent::class.java))
            assertEquals(1, f.endpoints.constructed().size)
            assertFalse(f.runtime.health.value.cellularReady)
            assertTrue(f.runtime.settings.takeover)
        }

    @Test fun destructionCancelsQueuedRestorationWithoutChangingTheRoleWhenCallsEnd(): Unit =
        Fixture(enabled = true, holder = "gateway").use { f ->
            f.connect()
            `when`(f.runtime.cellular.hasCalls).thenReturn(true)
            f.runtime.setTakeover(false)
            f.drain()
            assertTrue(f.runtime.health.value.takeoverOffPending)
            f.runtime.onForegroundStopped(f.serviceToken)
            f.drain()
            `when`(f.runtime.cellular.hasCalls).thenReturn(false)
            f.callsChanged(CallSnapshot())
            f.drain()
            verify(f.helper, never()).restoreDefaultDialer(anyString())
            assertFalse(f.runtime.health.value.takeover)
            assertTrue(f.runtime.health.value.holdsDialer)
            assertEquals(1, f.endpoints.constructed().size)
        }

    @Test fun oldServiceCannotStopReplacementAndExplicitRestartRecreatesResources(): Unit =
        Fixture(enabled = true).use { f ->
            f.connect()
            val old = f.serviceToken
            f.serviceToken = Any()
            f.runtime.onServiceCreated(f.serviceToken)
            f.runtime.onForegroundStopped(old)
            f.drain()
            verify(f.endpoints.constructed().single(), never()).stop(anyLong(), anyBoolean())
            f.promote()
            f.runtime.onForegroundStopped(f.serviceToken)
            f.drain()
            f.promote()
            assertEquals(2, f.endpoints.constructed().size)
            verify(f.endpoints.constructed().last()).start()
            verify(f.connector, times(2)).start()
        }

    @Test fun switchOffDuringCallKeepsResourcesUntilCallEnds(): Unit = Fixture(enabled = true).use { f ->
        f.connect()
        `when`(f.runtime.cellular.hasCalls).thenReturn(true)
        f.runtime.setTakeover(false)
        f.drain()
        assertTrue(f.runtime.health.value.takeoverOffPending)
        verify(f.endpoints.constructed().single(), never()).stop(anyLong(), anyBoolean())
        `when`(f.runtime.cellular.hasCalls).thenReturn(false)
        f.callsChanged(CallSnapshot())
        f.drain()
        verify(f.endpoints.constructed().single()).stop(releaseTransport = true)
        verify(f.bridges.constructed().single()).stop()
        assertFalse(f.runtime.health.value.takeover)
        assertFalse(f.runtime.health.value.takeoverOffPending)
    }

    private class Fixture(enabled: Boolean = false, var holder: String? = "phone", rejectLaunch: Boolean = false) : AutoCloseable {
        private val scopes = mutableListOf<AutoCloseable>()
        private val workerScopes = mutableListOf<AutoCloseable>()
        lateinit var connector: ShizukuConnector
            private set
        lateinit var endpoints: MockedConstruction<PjsipEndpoint>
            private set
        lateinit var bridges: MockedConstruction<CallBridge>
            private set
        lateinit var callsChanged: (CallSnapshot) -> Unit
            private set
        private var listener: ShizukuConnector.Listener? = null
        private var helperState = ShizukuConnector.State.STOPPED
        val context = mock(Context::class.java)
        val network = mock(ConnectivityManager::class.java)
        val helper = mock(IPrivilegedService::class.java)
        val runtime: GatewayRuntime
        private val worker: ScheduledThreadPoolExecutor
        var serviceToken = Any()

        init {
            val telecom = mock(TelecomManager::class.java)
            val role = mock(RoleManager::class.java)
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.packageName).thenReturn("gateway")
            `when`(context.packageManager).thenReturn(mock(PackageManager::class.java))
            `when`(context.getSystemService(TelecomManager::class.java)).thenReturn(telecom)
            `when`(context.getSystemService(RoleManager::class.java)).thenReturn(role)
            `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(network)
            `when`(telecom.defaultDialerPackage).thenAnswer { holder }
            `when`(role.isRoleAvailable(RoleManager.ROLE_DIALER)).thenReturn(true)
            if (rejectLaunch) doThrow(IllegalStateException("background launch denied")).`when`(context)
                .startForegroundService(any(Intent::class.java))
            `when`(helper.dialerState()).thenAnswer { result(DialerRoleResult.STATE) }
            `when`(helper.becomeDefaultDialer(anyString())).thenAnswer {
                holder = "gateway"
                result(DialerRoleResult.SUCCESS)
            }
            `when`(helper.restoreDefaultDialer(anyString())).thenAnswer {
                holder = it.getArgument<String>(0).ifEmpty { "phone" }
                result(DialerRoleResult.SUCCESS)
            }
            `when`(helper.setupCallAccess(anyString(), anyBoolean(), anyInt())).thenReturn(mock(Bundle::class.java))
            scopes += mockConstruction(SettingsStore::class.java) { store, _ ->
                `when`(store.load()).thenReturn(GatewaySettings(takeover = enabled, callAccess = CallAccess.DIALER,
                    server = "pbx.invalid", username = "test", password = "test"))
            }
            scopes += mockConstruction(DialerHandoffStore::class.java) { store, _ ->
                `when`(store.save(any(PreviousDialer::class.java) ?: PreviousDialer(0, ""))).thenReturn(true)
            }
            scopes += mockConstruction(CellularEndpoint::class.java) { endpoint, _ ->
                doAnswer { callsChanged = it.getArgument(0); null }.`when`(endpoint).onCallsChanged = any()
            }
            scopes += mockConstruction(ShizukuConnector::class.java) { mock, _ ->
                connector = mock
                `when`(mock.state).thenAnswer { helperState }
                `when`(mock.service).thenAnswer { if (helperState == ShizukuConnector.State.CONNECTED) helper else null }
                `when`(mock.detail).thenReturn("")
                doAnswer { listener = it.getArgument(0); null }.`when`(mock).addListener(
                    any(ShizukuConnector.Listener::class.java) ?: ShizukuConnector.Listener { _, _ -> })
                doAnswer { transition(ShizukuConnector.State.BINDING); null }.`when`(mock).start()
                doAnswer { transition(ShizukuConnector.State.STOPPED); null }.`when`(mock).stop()
            }
            val constructor = GatewayRuntime::class.java.getDeclaredConstructor(Context::class.java)
            constructor.isAccessible = true
            runtime = constructor.newInstance(context)
            val field = GatewayRuntime::class.java.getDeclaredField("worker")
            field.isAccessible = true
            worker = field.get(runtime) as ScheduledThreadPoolExecutor
            // Mockito scopes belong to this thread; idle timeout is tested separately.
            worker.setKeepAliveTime(1, TimeUnit.DAYS)
            worker.submit {
                workerScopes += mockStatic(Log::class.java)
                workerScopes += mockStatic(Process::class.java)
                workerScopes += mockConstruction(ComponentName::class.java)
                workerScopes += mockConstruction(Intent::class.java)
                workerScopes += mockConstruction(ConnectivityManager.NetworkCallback::class.java)
                endpoints = mockConstruction(PjsipEndpoint::class.java) { endpoint, construction ->
                    `when`(endpoint.config).thenReturn(construction.arguments()[0] as PjsipConfig)
                    `when`(endpoint.status).thenReturn(EndpointStatus(false, "test endpoint"))
                }.also { workerScopes += it }
                bridges = mockConstruction(CallBridge::class.java).also { workerScopes += it }
            }.get(3, TimeUnit.SECONDS)
            runtime.start()
            drain()
        }

        private fun result(status: String): Bundle = mock(Bundle::class.java).also { bundle ->
            `when`(bundle.getString("status")).thenReturn(status)
            `when`(bundle.getString("holder")).thenReturn(holder)
            `when`(bundle.containsKey("user_id")).thenReturn(true)
            `when`(bundle.containsKey("in_call")).thenReturn(true)
        }
        private fun transition(state: ShizukuConnector.State) {
            helperState = state
            listener?.onChanged(state, connector.service)
        }
        fun promote() {
            runtime.onServiceCreated(serviceToken)
            runtime.onForegroundStarted(serviceToken)
            drain()
        }
        fun connect() {
            promote()
            transition(ShizukuConnector.State.CONNECTED)
            drain()
        }
        fun drain() {
            do {
                val pending = worker.submit<Boolean> {
                    worker.queue.any { it !is ScheduledFuture<*> || it.getDelay(TimeUnit.NANOSECONDS) <= 0 }
                }.get(3, TimeUnit.SECONDS)
            } while (pending)
        }
        override fun close() {
            runtime.onForegroundStopped(serviceToken)
            drain()
            worker.submit { workerScopes.asReversed().forEach { it.close() } }.get(3, TimeUnit.SECONDS)
            worker.shutdownNow()
            scopes.asReversed().forEach { it.close() }
        }
    }
}
