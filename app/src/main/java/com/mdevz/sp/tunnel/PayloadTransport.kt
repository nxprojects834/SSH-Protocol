package com.mdevz.sp.tunnel

import com.mdevz.sp.payload.PayloadContext
import com.mdevz.sp.payload.PayloadEngineFactory
import com.mdevz.sp.tunnel.config.ConnectionSnapshot

object PayloadTransport {

    suspend fun establish(
        connection: TunnelConnection,
        snapshot: ConnectionSnapshot,
        context: PayloadContext
    ): TunnelConnection {
        check(snapshot.payloadEnabled) {
            "Payload transport requires payload to be enabled"
        }

        require(snapshot.payload.isNotEmpty()) {
            "Payload transport requires non-empty payload"
        }

        check(connection.isOpen) {
            "Cannot execute payload on a closed transport"
        }

        val payloadEngine =
            PayloadEngineFactory.create(
                snapshot.payloadMode
            )

        try {
            payloadEngine.execute(
                payload = snapshot.payload,
                context = context,
                connection = connection
            )

            return connection
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }
}
