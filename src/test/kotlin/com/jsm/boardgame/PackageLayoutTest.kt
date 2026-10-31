package com.jsm.boardgame

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageLayoutTest {

    @Test
    fun `모든 kt 파일의 package 선언은 디렉터리 경로와 같다`() {
        val mismatches = listOf("src/main/kotlin", "src/test/kotlin").map(Path::of).flatMap { root ->
            Files.walk(root).use { files ->
                files.filter { it.extension == "kt" }.toList().mapNotNull { file ->
                    val declared = declaredPackage(file.readLines())
                    val expected = root.relativize(file.parent).joinToString(".")
                    if (declared == expected) null else "$file: 선언=$declared, 경로=$expected"
                }
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `package 줄의 세미콜론과 주석을 걷어내고 앞의 애너테이션·주석 줄은 건너뛴다`() {
        val lines = listOf("@file:Suppress(\"x\")", "", "// c", "/* c */", "package a.b.c; // 끝", "package z")
        assertEquals("a.b.c", declaredPackage(lines))
        assertEquals("a.b", declaredPackage(listOf("package a.b /* c */")))
        assertEquals("a.b", declaredPackage(listOf("package a.b // c")))
        assertNull(declaredPackage(listOf("import x")))
    }

    private fun declaredPackage(lines: List<String>): String? =
        lines.map(String::trim).firstOrNull { it.startsWith("package ") }
            ?.removePrefix("package ")
            ?.replace(Regex("/\\*.*?\\*/"), "")
            ?.substringBefore("//")
            ?.trim()?.removeSuffix(";")?.trim()
}
