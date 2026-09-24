package com.mdevz.sp.payload.enhanced

import com.mdevz.sp.tunnel.TunnelConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class PayloadExecutor {

    suspend fun execute(
        commands: List<PayloadCommand>,
        connection: TunnelConnection
    ) {
        check(connection.isOpen) {
            "Cannot execute payload on a closed connection"
        }

        for (command in commands) {
            coroutineContext.ensureActive()

            when (command) {
                is PayloadCommand.Write -> {
                    if (command.data.isNotEmpty()) {
                        connection.write(command.data)
                        connection.flush()
                    }
                }

                is PayloadCommand.Delay -> {
                    require(command.milliseconds >= 0)
                    delay(command.milliseconds)
                }
            }
        }
    }
}
