package com.mdevz.sp.payload.enhanced

sealed interface PayloadToken {

    data class Text(
        val value: String
    ) : PayloadToken

    data object Split : PayloadToken

    data object DelayedSplit : PayloadToken
}
