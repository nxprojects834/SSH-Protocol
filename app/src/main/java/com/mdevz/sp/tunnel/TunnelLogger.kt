package com.mdevz.sp.tunnel

interface TunnelLogger {
    fun info(message: String)

    fun error(
        message: String,
        cause: Throwable? = null
    )
}
