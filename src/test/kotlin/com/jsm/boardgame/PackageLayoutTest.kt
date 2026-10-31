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

    @Test
    fun `여러 줄 블록 주석 안의 package 줄은 무시한다`() {
        val lines = listOf("/*", " * 예전엔", "package old.name", " */", "package real.name")
        assertEquals("real.name", declaredPackage(lines))
        assertEquals("real.name", declaredPackage(listOf("/* 시작", "package old", "끝 */ package real.name")))
    }

    private fun declaredPackage(lines: List<String>): String? =
        stripBlockComments(lines).map(String::trim).firstOrNull { it.startsWith("package ") }
            ?.removePrefix("package ")
            ?.replace(Regex("/\\*.*?\\*/"), "")
            ?.substringBefore("//")
            ?.trim()?.removeSuffix(";")?.trim()

    // 줄 단위로 `/* ... */`(여러 줄 포함)를 걷어낸다. 같은 줄의 한 줄짜리 블록 주석은 declaredPackage 가 처리한다.
    private fun stripBlockComments(lines: List<String>): List<String> {
        var inBlock = false
        return lines.map { line ->
            val out = StringBuilder()
            var i = 0
            while (i < line.length) {
                if (inBlock) {
                    val end = line.indexOf("*/", i)
                    if (end < 0) break
                    inBlock = false
                    i = end + 2
                } else {
                    val start = line.indexOf("/*", i)
                    if (start < 0) { out.append(line, i, line.length); break }
                    out.append(line, i, start)
                    val end = line.indexOf("*/", start + 2)
                    if (end < 0) { inBlock = true; break }
                    i = end + 2
                }
            }
            out.toString()
        }
    }
}
