package com.mdevz.sp.core.config

import com.mdevz.sp.core.model.SshProfile

interface ProfileRepository {
    fun save(
        id: String,
        profile: SshProfile
    )

    fun load(
        id: String
    ): SshProfile?

    fun delete(
        id: String
    )

    fun listIds(): List<String>
}
