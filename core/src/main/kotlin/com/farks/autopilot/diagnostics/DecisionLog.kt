package com.farks.autopilot.diagnostics

import com.farks.autopilot.models.LogLevel

data class LogEntry(val timeMs: Long, val level: LogLevel, val message: String)

/** Registro circular, seguro entre hilos, de decisiones y errores para el modo diagnóstico. */
class DecisionLog(private val capacity: Int = 400) {
    private val entries = ArrayDeque<LogEntry>()

    @Synchronized
    fun add(timeMs: Long, level: LogLevel, message: String) {
        entries.addLast(LogEntry(timeMs, level, message))
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()

    @Synchronized
    fun errors(): List<LogEntry> = entries.filter { it.level != LogLevel.INFO }

    @Synchronized
    fun clear() = entries.clear()
}
