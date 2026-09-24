package com.mdevz.sp.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TunnelLogEntry(
    val timestamp: Long,
    val level: TunnelLogLevel,
    val message: String
)

enum class TunnelLogLevel {
    INFO,
    ERROR
}

class MemoryTunnelLogger(
    private val maxEntries: Int = 500
) : TunnelLogger {

    private val lock = Any()

    private val _entries =
        MutableStateFlow<List<TunnelLogEntry>>(
            emptyList()
        )

    val entries: StateFlow<List<TunnelLogEntry>> =
        _entries.asStateFlow()

    override fun info(message: String) {
        append(
            TunnelLogLevel.INFO,
            sanitize(message)
        )
    }

    override fun error(
        message: String,
        cause: Throwable?
    ) {
        val safe =
            if (cause == null) {
                message
            } else {
                val detailCause =
                    deepestMeaningfulCause(
                        cause
                    )

                val context =
                    cause.message
                        ?.takeIf { it.isNotBlank() }

                val detail =
                    detailCause.message
                        ?.takeIf { it.isNotBlank() }

                val contextText =
                    when {
                        context == null ->
                            message

                        context == message ->
                            message

                        else ->
                            "$message: $context"
                    }

                if (
                    detailCause === cause
                ) {
                    if (context == null) {
                        "$message (${cause::class.java.simpleName})"
                    } else {
                        "$contextText (${cause::class.java.simpleName})"
                    }
                } else if (
                    detail == null ||
                    detail == context
                ) {
                    "$contextText (${detailCause::class.java.simpleName})"
                } else {
                    "$contextText: $detail (${detailCause::class.java.simpleName})"
                }
            }

        append(
            TunnelLogLevel.ERROR,
            sanitize(safe)
        )
    }

    private fun deepestMeaningfulCause(
        failure: Throwable
    ): Throwable {
        var current =
            failure

        val visited =
            mutableSetOf<Throwable>()

        while (
            visited.add(current)
        ) {
            val next =
                current.cause
                    ?: break

            if (next === current) {
                break
            }

            current =
                next
        }

        return current
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
    }

    private fun append(
        level: TunnelLogLevel,
        message: String
    ) {
        synchronized(lock) {
            val updated =
                _entries.value +
                    TunnelLogEntry(
                        timestamp =
                            System.currentTimeMillis(),
                        level = level,
                        message = message
                    )

            _entries.value =
                if (updated.size > maxEntries) {
                    updated.takeLast(maxEntries)
                } else {
                    updated
                }
        }
    }

    private fun sanitize(
        message: String
    ): String {
        return message
            .replace('\r', ' ')
            .replace('\n', ' ')
            .take(1000)
    }
}
