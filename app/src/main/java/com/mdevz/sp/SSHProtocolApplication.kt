package com.mdevz.sp

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale

class SSHProtocolApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous =
            Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler {
            thread,
            throwable ->

            try {
                persistCrash(
                    thread,
                    throwable
                )
            } catch (_: Throwable) {
            }

            if (previous != null) {
                previous.uncaughtException(
                    thread,
                    throwable
                )
            } else {
                android.os.Process.killProcess(
                    android.os.Process.myPid()
                )
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun persistCrash(
        thread: Thread,
        throwable: Throwable
    ) {
        val occurredAt =
            System.currentTimeMillis()

        val relevant =
            findRelevantThrowableAndFrame(
                throwable
            )

        val throwableName =
            relevant.first
                .javaClass
                .simpleName
                .takeIf {
                    it.isNotBlank()
                }
                ?: relevant.first
                    .javaClass
                    .name

        val message =
            relevant.first.message
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val point =
            relevant.second
                ?.let {
                    frame ->
                    buildString {
                        append(
                            frame.fileName
                                ?: frame.className
                        )

                        if (
                            frame.lineNumber > 0
                        ) {
                            append(':')
                            append(
                                frame.lineNumber
                            )
                        }
                    }
                }
                ?: "Unknown source"

        val writer =
            StringWriter()

        throwable.printStackTrace(
            PrintWriter(writer)
        )

        val timestamp =
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.US
            ).format(
                Date(occurredAt)
            )

        val report =
            buildString {
                append(
                    "SSHProtocol crash"
                )
                append('\n')
                append(timestamp)
                append('\n')
                append('\n')

                append("Error: ")
                append(throwableName)

                if (message != null) {
                    append(": ")
                    append(message)
                }

                append('\n')
                append("Point: ")
                append(point)

                append('\n')
                append("Thread: ")
                append(thread.name)

                append('\n')
                append('\n')

                append(
                    writer
                        .toString()
                        .trim()
                )
            }

        getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putString(
                KEY_REPORT,
                report
            )
            .commit()
    }

    private fun findRelevantThrowableAndFrame(
        root: Throwable
    ): Pair<Throwable, StackTraceElement?> {
        val visited =
            IdentityHashMap<
                Throwable,
                Boolean
            >()

        var current: Throwable? =
            root

        var relevantThrowable =
            root

        var relevantFrame:
            StackTraceElement? =
            root.stackTrace
                .firstOrNull()

        while (
            current != null &&
            visited.size <
                MAX_CAUSE_DEPTH
        ) {
            if (
                visited.put(
                    current,
                    true
                ) != null
            ) {
                break
            }

            val appFrame =
                current.stackTrace
                    .firstOrNull {
                        frame ->
                        frame.className
                            .startsWith(
                                APP_PACKAGE_PREFIX
                            )
                    }

            if (appFrame != null) {
                relevantThrowable =
                    current
                relevantFrame =
                    appFrame
            }

            current =
                current.cause
        }

        return Pair(
            relevantThrowable,
            relevantFrame
        )
    }

    companion object {

        private const val PREFS =
            "crash_handler"

        private const val KEY_REPORT =
            "pending_crash_report"

        private const val APP_PACKAGE_PREFIX =
            "com.mdevz.sp."

        private const val MAX_CAUSE_DEPTH =
            64

        fun consumeCrashReport(
            context: Context
        ): String? {
            val preferences =
                context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

            val report =
                preferences.getString(
                    KEY_REPORT,
                    null
                )
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: return null

            preferences.edit()
                .remove(
                    KEY_REPORT
                )
                .apply()

            return report
        }
    }
}
