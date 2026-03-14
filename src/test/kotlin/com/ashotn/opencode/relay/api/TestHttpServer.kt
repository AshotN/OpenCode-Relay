package com.ashotn.opencode.relay.api

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

fun withTestServer(block: (HttpServer, Int) -> Unit) {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    try {
        server.start()
        block(server, server.address.port)
    } finally {
        server.stop(0)
    }
}
