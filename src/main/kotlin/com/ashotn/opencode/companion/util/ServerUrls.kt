package com.ashotn.opencode.companion.util

fun serverUrl(port: Int, path: String = ""): String = "http://localhost:$port$path"
