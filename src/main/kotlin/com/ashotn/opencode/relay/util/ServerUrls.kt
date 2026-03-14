package com.ashotn.opencode.relay.util

fun serverUrl(port: Int, path: String = ""): String = "http://localhost:$port$path"
