package com.ashotn.opencode.util

fun serverUrl(port: Int, path: String = ""): String = "http://localhost:$port$path"
