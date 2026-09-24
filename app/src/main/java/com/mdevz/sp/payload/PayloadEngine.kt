package com.mdevz.sp.payload

import com.mdevz.sp.tunnel.TunnelConnection

interface PayloadEngine {

    suspend fun execute(
        payload: String,
        context: PayloadContext,
        connection: TunnelConnection
    )
}
