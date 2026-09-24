package hev.htproxy

class TProxyService {

    external fun TProxyStartService(
        configPath: String,
        fd: Int
    ): Boolean

    external fun TProxyStopService(): Boolean

    external fun TProxyIsRunning(): Boolean

    external fun TProxyGetStats(): LongArray

    companion object {
        init {
            System.loadLibrary(
                "sshprotocol"
            )
        }
    }
}
