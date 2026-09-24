package com.mdevz.sp.core.util

import java.io.EOFException
import java.io.InputStream

fun InputStream.readExactly(length: Int): ByteArray {
    require(length >= 0)

    val result = ByteArray(length)
    var offset = 0

    while (offset < length) {
        val read = read(
            result,
            offset,
            length - offset
        )

        if (read < 0) {
            throw EOFException(
                "Unexpected EOF: expected $length bytes, received $offset"
            )
        }

        if (read == 0) {
           
            continue
        }

        offset += read
    }

    return result
}
