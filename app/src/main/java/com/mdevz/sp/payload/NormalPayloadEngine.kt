package com.mdevz.sp.payload

import com.mdevz.sp.tunnel.TunnelConnection
import java.nio.charset.StandardCharsets

class NormalPayloadEngine : PayloadEngine {

    override suspend fun execute(
        payload: String,
        context: PayloadContext,
        connection: TunnelConnection
    ) {
        require(payload.isNotEmpty()) {
            "Payload cannot be empty"
        }

        check(connection.isOpen) {
            "Cannot execute payload on a closed connection"
        }

        val resolved =
            PayloadPlaceholderResolver.resolve(
                input = payload,
                context = context
            )

        connection.write(
            resolved.toByteArray(StandardCharsets.UTF_8)
        )

        connection.flush()
    }
}
