package com.mdevz.sp.tunnel

data class TunnelFailure(
    val stage: String,
    val message: String,
    val cause: Throwable
)
