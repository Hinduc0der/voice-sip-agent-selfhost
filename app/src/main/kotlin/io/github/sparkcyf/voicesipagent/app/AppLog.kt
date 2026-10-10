package io.github.sparkcyf.voicesipagent.app

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logcat plus a small in-memory tail for the UI. Callers must not log PCM, passwords or full numbers. */
object AppLog {
    private const val TAG = "VoiceSipAgent"
    private const val KEEP = 200
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()
    private val _tail = MutableStateFlow<List<String>>(emptyList())
    val tail: StateFlow<List<String>> = _tail

    fun i(msg: String) = add(msg) { Log.i(TAG, it) }

    fun w(msg: String, t: Throwable? = null) = add(if (t == null) msg else "$msg: $t") { Log.w(TAG, it, t) }

    private fun add(msg: String, sink: (String) -> Unit) {
        sink(msg)
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())} $msg")
            while (lines.size > KEEP) lines.removeFirst()
            _tail.value = lines.toList()
        }
    }
}
