package com.mdevz.sp.socks

import java.io.IOException

class Socks5Exception(
    message: String,
    val replyCode: Int = Socks5Protocol.REPLY_GENERAL_FAILURE,
    cause: Throwable? = null
) : IOException(message, cause)
