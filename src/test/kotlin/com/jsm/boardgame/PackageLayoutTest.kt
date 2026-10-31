package com.jsm.boardgame

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.test.assertTrue

class PackageLayoutTest {

    @Test
    fun `모든 kt 파일의 package 선언은 디렉터리 경로와 같다`() {
        val mismatches = listOf("src/main/kotlin", "src/test/kotlin").map(Path::of).flatMap { root ->
            Files.walk(root).use { files ->
                files.filter { it.extension == "kt" }.toList().mapNotNull { file ->
                    val declared = file.readLines().firstOrNull { it.startsWith("package ") }
                        ?.removePrefix("package ")?.trim()
                    val expected = root.relativize(file.parent).joinToString(".")
                    if (declared == expected) null else "$file: 선언=$declared, 경로=$expected"
                }
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }
}
