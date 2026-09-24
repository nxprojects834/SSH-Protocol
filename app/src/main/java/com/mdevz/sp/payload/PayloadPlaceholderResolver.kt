package com.mdevz.sp.payload

object PayloadPlaceholderResolver {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 6.3; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/44.0.2403.130 Safari/537.36"

    fun resolve(
        input: String,
        context: PayloadContext
    ): String {
        val out = StringBuilder(input.length + 32)
        var index = 0

        while (index < input.length) {
            when {
                input[index] == '\\' &&
                    index + 1 < input.length &&
                    input[index + 1] == 'r' -> {
                    out.append('\r')
                    index += 2
                }

                input[index] == '\\' &&
                    index + 1 < input.length &&
                    input[index + 1] == 'n' -> {
                    out.append('\n')
                    index += 2
                }

                input[index] == '[' -> {
                    val close = input.indexOf(']', index + 1)

                    if (close < 0) {
                        out.append(input[index])
                        index++
                        continue
                    }

                    val token = input
                        .substring(index + 1, close)
                        .trim()
                        .lowercase()

                    when (token) {
                        "cr" -> {
                            out.append('\r')
                        }

                        "lf" -> {
                            out.append('\n')
                        }

                        "crlf" -> {
                            out.append('\r')
                            out.append('\n')
                        }

                        "lfcr" -> {
                            out.append('\n')
                            out.append('\r')
                        }

                        "host",
                        "ssh_host" -> {
                            out.append(context.sshHost)
                        }

                        "port",
                        "ssh_port" -> {
                            out.append(context.sshPort)
                        }

                        "host_port",
                        "ssh" -> {
                            out.append(context.hostPort)
                        }

                        "proxy_host" -> {
                            if (context.proxyHost.isNotBlank()) {
                                out.append(context.proxyHost)
                            } else {
                                out.append(
                                    input,
                                    index,
                                    close + 1
                                )
                            }
                        }

                        "proxy_port" -> {
                            if (context.proxyPort in 1..65535) {
                                out.append(context.proxyPort)
                            } else {
                                out.append(
                                    input,
                                    index,
                                    close + 1
                                )
                            }
                        }

                        "proxy" -> {
                            if (
                                context.proxyHost.isNotBlank() &&
                                context.proxyPort in 1..65535
                            ) {
                                out.append(context.proxyHost)
                                out.append(':')
                                out.append(context.proxyPort)
                            } else {
                                out.append(
                                    input,
                                    index,
                                    close + 1
                                )
                            }
                        }

                        "ua" -> {
                            out.append(USER_AGENT)
                        }

                        else -> {
                            out.append(
                                input,
                                index,
                                close + 1
                            )
                        }
                    }

                    index = close + 1
                }

                else -> {
                    out.append(input[index])
                    index++
                }
            }
        }

        return out.toString()
    }
}
