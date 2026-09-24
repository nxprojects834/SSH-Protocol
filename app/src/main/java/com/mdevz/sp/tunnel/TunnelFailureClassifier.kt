package com.mdevz.sp.tunnel

import com.mdevz.sp.ssh.CorruptKnownHostException
import com.mdevz.sp.ssh.HostKeyMismatchException
import com.mdevz.sp.ssh.SshAuthenticationException
import com.mdevz.sp.ssh.UnknownHostKeyException
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

enum class ReconnectDecision {
    RETRY,
    TERMINAL
}

class TunnelRuntimeLostException(
    message: String,
    cause: Throwable? = null
) : IOException(
    message,
    cause
)

class TunnelTeardownException(
    message: String,
    cause: Throwable
) : IOException(
    message,
    cause
)

object TunnelFailureClassifier {

    fun classify(
        failure: Throwable
    ): ReconnectDecision {
        val graph =
            throwableGraph(failure)

        if (
            graph.any {
                it is UnknownHostKeyException ||
                    it is HostKeyMismatchException ||
                    it is CorruptKnownHostException ||
                    it is SshAuthenticationException
            }
        ) {
            return ReconnectDecision.TERMINAL
        }

        if (
            graph.any {
                it is TunnelTeardownException
            }
        ) {
            return ReconnectDecision.TERMINAL
        }

        if (
            graph.any {
                it is IllegalArgumentException
            }
        ) {
            return ReconnectDecision.TERMINAL
        }

        if (
            graph.any {
                it is TunnelRuntimeLostException
            }
        ) {
            return ReconnectDecision.RETRY
        }

        if (
            graph.any {
                it is IOException
            }
        ) {
            return ReconnectDecision.RETRY
        }

        return ReconnectDecision.TERMINAL
    }

    private fun throwableGraph(
        root: Throwable
    ): List<Throwable> {
        val visited =
            Collections.newSetFromMap(
                IdentityHashMap<Throwable, Boolean>()
            )

        val pending =
            ArrayDeque<Throwable>()

        val result =
            mutableListOf<Throwable>()

        pending.add(root)

        while (pending.isNotEmpty()) {
            val current =
                pending.removeFirst()

            if (!visited.add(current)) {
                continue
            }

            result += current

            current.cause
                ?.let {
                    pending.addLast(it)
                }

            current.suppressed
                .forEach {
                    pending.addLast(it)
                }
        }

        return result
    }
}
