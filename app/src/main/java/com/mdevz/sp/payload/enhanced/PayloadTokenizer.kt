package com.mdevz.sp.payload.enhanced

class PayloadTokenizer {

    private val immediateSplitTokens = setOf(
        "split",
        "splitnodelay",
        "instant_split"
    )

    private val delayedSplitTokens = setOf(
        "delay",
        "delay_split",
        "split_delay"
    )

    fun tokenize(input: String): List<PayloadToken> {
        if (input.isEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<PayloadToken>()
        val text = StringBuilder()

        fun emitText() {
            if (text.isNotEmpty()) {
                result += PayloadToken.Text(text.toString())
                text.setLength(0)
            }
        }

        var index = 0

        while (index < input.length) {
            if (input[index] != '[') {
                text.append(input[index])
                index++
                continue
            }

            val close = input.indexOf(']', index + 1)

            if (close < 0) {
                text.append(input[index])
                index++
                continue
            }

            val raw = input.substring(
                index + 1,
                close
            )

            val normalized =
                raw.trim().lowercase()

            when {
                normalized in immediateSplitTokens -> {
                    emitText()
                    result += PayloadToken.Split
                    index = close + 1
                }

                normalized in delayedSplitTokens -> {
                    emitText()
                    result += PayloadToken.DelayedSplit
                    index = close + 1
                }

                else -> {
                    text.append(
                        input,
                        index,
                        close + 1
                    )

                    index = close + 1
                }
            }
        }

        emitText()

        return result
    }
}
