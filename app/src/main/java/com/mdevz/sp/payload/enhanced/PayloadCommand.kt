package com.mdevz.sp.payload.enhanced

sealed interface PayloadCommand {

    data class Write(
        val data: ByteArray
    ) : PayloadCommand {

        override fun equals(other: Any?): Boolean {
            return other is Write &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int =
            data.contentHashCode()
    }

    data class Delay(
        val milliseconds: Long
    ) : PayloadCommand
}
