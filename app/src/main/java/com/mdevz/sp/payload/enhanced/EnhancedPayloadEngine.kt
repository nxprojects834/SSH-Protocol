package com.mdevz.sp.payload.enhanced

import com.mdevz.sp.payload.PayloadContext
import com.mdevz.sp.payload.PayloadEngine
import com.mdevz.sp.tunnel.TunnelConnection

class EnhancedPayloadEngine(
    private val tokenizer: PayloadTokenizer =
        PayloadTokenizer(),
    private val parser: PayloadParser =
        PayloadParser(),
    private val executor: PayloadExecutor =
        PayloadExecutor()
) : PayloadEngine {

    override suspend fun execute(
        payload: String,
        context: PayloadContext,
        connection: TunnelConnection
    ) {
        require(payload.isNotEmpty()) {
            "Enhanced payload cannot be empty"
        }

        val tokens = tokenizer.tokenize(payload)

        val commands = parser.parse(
            tokens = tokens,
            context = context
        )

        check(commands.any {
            it is PayloadCommand.Write
        }) {
            "Enhanced payload produced no data"
        }

        executor.execute(
            commands = commands,
            connection = connection
        )
    }
}
