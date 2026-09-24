package com.mdevz.sp.ssh

interface KnownHostStore {
    fun find(host: String, port: Int): HostKeyRecord?
    fun trust(record: HostKeyRecord)
    fun remove(host: String, port: Int)
}
