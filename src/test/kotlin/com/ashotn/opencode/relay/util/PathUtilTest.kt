package com.ashotn.opencode.relay.util

import kotlin.test.Test
import kotlin.test.assertEquals

class PathUtilTest {
    @Test
    fun `outside path is returned unchanged when project base does not match`() {
        assertEquals(
            "D:\\other\\src\\Main.kt",
            "D:\\other\\src\\Main.kt".toProjectRelativePath("C:/Users/VM/project"),
        )
    }

    @Test
    fun `windows project relative path strips base case insensitively`() {
        assertEquals(
            "src/Main.kt",
            "c:\\users\\vm\\project\\src\\Main.kt".toProjectRelativePath("C:/Users/VM/project"),
        )
    }

    @Test
    fun `windows absolute path inside project uses project base casing`() {
        assertEquals(
            "C:/Users/VM/project/src/Main.kt",
            toAbsolutePath("C:/Users/VM/project", "c:\\users\\vm\\project\\src\\Main.kt"),
        )
    }

    @Test
    fun `windows absolute path outside project keeps its own casing`() {
        assertEquals(
            "d:/other/src/Main.kt",
            toAbsolutePath("C:/Users/VM/project", "d:\\other\\src\\Main.kt"),
        )
    }
}
