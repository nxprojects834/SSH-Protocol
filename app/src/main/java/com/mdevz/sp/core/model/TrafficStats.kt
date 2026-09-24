package com.mdevz.sp.core.model

data class TrafficStats(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val connectedAt: Long = 0L
)
