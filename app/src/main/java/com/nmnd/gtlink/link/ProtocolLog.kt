package com.nmnd.gtlink.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A bounded, in-memory trace of what crossed the link. The app was written against
 * Gadgetbridge's protocol notes rather than a live watch, so this log is the first
 * thing to look at when a command does nothing.
 */
class ProtocolLog(private val capacity: Int = 600) {
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(line: String) {
        val stamped = format.format(Date()) + "  " + line.take(600)
        synchronized(this) {
            val next = _lines.value + stamped
            _lines.value = if (next.size > capacity) next.takeLast(capacity) else next
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
