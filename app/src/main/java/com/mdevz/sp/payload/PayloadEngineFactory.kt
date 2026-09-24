package com.mdevz.sp.payload

import com.mdevz.sp.core.model.PayloadMode
import com.mdevz.sp.payload.enhanced.EnhancedPayloadEngine

object PayloadEngineFactory {

    fun create(
        mode: PayloadMode
    ): PayloadEngine =
        when (mode) {
            PayloadMode.NORMAL ->
                NormalPayloadEngine()

            PayloadMode.ENHANCED ->
                EnhancedPayloadEngine()
        }
}
