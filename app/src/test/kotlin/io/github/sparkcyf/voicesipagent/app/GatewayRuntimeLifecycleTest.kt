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
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.telephony.CellularEndpoint
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class GatewayRuntimeLifecycleTest {
    @Test fun switchOnWaitsForHelperThenAcquiresCapturedDialer(): Unit = Fixture().use { f ->
        f.runtime.setTakeover(true)
        f.drain()
        assertTrue(f.runtime.health.value.dialerChangePending)
        verify(f.helper, never()).becomeDefaultDialer(anyString())
        f.connect()
        verify(f.helper).becomeDefaultDialer("phone")
        assertFalse(f.runtime.health.value.dialerChangePending)
    }

    @Test fun switchOnWithNoDialerWaitsForHelperThenAcquiresEmptyRole(): Unit = Fixture().use { f ->
        f.holder = null
        f.runtime.setTakeover(true)
        f.drain()
        assertTrue(f.runtime.health.value.dialerChangePending)
        assertFalse(f.runtime.health.value.dialerChangeFailed)
        verify(f.helper, never()).becomeDefaultDialer(anyString())
        f.connect()
        verify(f.helper).becomeDefaultDialer("")
        assertEquals("gateway", f.holder)
        assertFalse(f.runtime.health.value.dialerChangePending)
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
            Fixture().use { f ->
                f.holder = initialHolder
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

    @Test fun savedSwitchOnDoesNotAcquireDialerOnStartup(): Unit = Fixture(enabled = true).use { f ->
        f.connect()
        verify(f.helper, never()).becomeDefaultDialer(anyString())
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

    private class Fixture(enabled: Boolean = false) : AutoCloseable {
        private val scopes = mutableListOf<AutoCloseable>()
        private val workerScopes = mutableListOf<AutoCloseable>()
        private lateinit var connector: ShizukuConnector
        private var listener: ShizukuConnector.Listener? = null
        private var helperState = ShizukuConnector.State.STOPPED
        var holder: String? = "phone"
        val helper = mock(IPrivilegedService::class.java)
        val runtime: GatewayRuntime
        private val worker: ScheduledThreadPoolExecutor

        init {
            val context = mock(Context::class.java)
            val telecom = mock(TelecomManager::class.java)
            val role = mock(RoleManager::class.java)
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.packageName).thenReturn("gateway")
            `when`(context.packageManager).thenReturn(mock(PackageManager::class.java))
            `when`(context.getSystemService(TelecomManager::class.java)).thenReturn(telecom)
            `when`(context.getSystemService(RoleManager::class.java)).thenReturn(role)
            `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(mock(ConnectivityManager::class.java))
            `when`(telecom.defaultDialerPackage).thenAnswer { holder }
            `when`(role.isRoleAvailable(RoleManager.ROLE_DIALER)).thenReturn(true)
            `when`(helper.dialerState()).thenAnswer { result(DialerRoleResult.STATE) }
            `when`(helper.becomeDefaultDialer(anyString())).thenAnswer {
                holder = "gateway"
                result(DialerRoleResult.SUCCESS)
            }
            `when`(helper.setupCallAccess(anyString(), anyBoolean(), anyInt())).thenReturn(mock(Bundle::class.java))
            scopes += mockConstruction(SettingsStore::class.java) { store, _ ->
                `when`(store.load()).thenReturn(GatewaySettings(takeover = enabled, callAccess = CallAccess.DIALER))
            }
            scopes += mockConstruction(DialerHandoffStore::class.java) { store, _ ->
                `when`(store.save(any(PreviousDialer::class.java) ?: PreviousDialer(0, ""))).thenReturn(true)
            }
            scopes += mockConstruction(CellularEndpoint::class.java)
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
        fun connect() {
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
            runtime.setTakeover(false)
            drain()
            worker.submit { workerScopes.asReversed().forEach { it.close() } }.get(3, TimeUnit.SECONDS)
            worker.shutdownNow()
            scopes.asReversed().forEach { it.close() }
        }
    }
}
