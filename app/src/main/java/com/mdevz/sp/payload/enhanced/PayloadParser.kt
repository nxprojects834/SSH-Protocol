package com.mdevz.sp.payload.enhanced

import com.mdevz.sp.payload.PayloadContext
import com.mdevz.sp.payload.PayloadPlaceholderResolver
import java.nio.charset.StandardCharsets

class PayloadParser(
    private val delayedSplitMillis: Long = 1_000L
) {

    init {
        require(delayedSplitMillis >= 0)
    }

    fun parse(
        tokens: List<PayloadToken>,
        context: PayloadContext
    ): List<PayloadCommand> {

        if (tokens.isEmpty()) {
            return emptyList()
        }

        val commands = mutableListOf<PayloadCommand>()
        val chunk = StringBuilder()

        var pendingDelayAfterChunk = false

        fun emitChunk(
            hasFollowingChunk: Boolean
        ) {
            if (chunk.isEmpty()) {
                pendingDelayAfterChunk = false
                return
            }

            val resolved =
                PayloadPlaceholderResolver.resolve(
                    input = chunk.toString(),
                    context = context
                )

            commands += PayloadCommand.Write(
                resolved.toByteArray(
                    StandardCharsets.UTF_8
                )
            )

            chunk.setLength(0)

            if (
                pendingDelayAfterChunk &&
                hasFollowingChunk
            ) {
                commands += PayloadCommand.Delay(
                    delayedSplitMillis
                )
            }

            pendingDelayAfterChunk = false
        }

        tokens.forEachIndexed { index, token ->
            when (token) {
                is PayloadToken.Text -> {
                    chunk.append(token.value)
                }

                PayloadToken.Split -> {
                    emitChunk(
                        hasFollowingChunk =
                            hasTextAfter(tokens, index)
                    )
                }

                PayloadToken.DelayedSplit -> {
                    pendingDelayAfterChunk = true

                    emitChunk(
                        hasFollowingChunk =
                            hasTextAfter(tokens, index)
                    )
                }
            }
        }

        emitChunk(hasFollowingChunk = false)

        return commands
    }

    private fun hasTextAfter(
        tokens: List<PayloadToken>,
        currentIndex: Int
    ): Boolean {
        for (i in currentIndex + 1 until tokens.size) {
            val token = tokens[i]

            if (
                token is PayloadToken.Text &&
                token.value.isNotEmpty()
            ) {
                return true
            }
        }

        return false
    }
}
