package com.jsm.boardgame.user.infrastructure.storage

import com.jsm.boardgame.user.domain.model.ProfileImageKey
import com.jsm.boardgame.user.domain.model.ProfileImageType
import com.jsm.boardgame.user.domain.model.UserId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalProfileImageStorageTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `사용자 디렉터리 아래에 판별된 확장자로 파일을 쓴다`() {
        val storage = LocalProfileImageStorage(dir.toString())
        val bytes = byteArrayOf(1, 2, 3)

        val key = storage.store(UserId(7), bytes, ProfileImageType.JPEG)

        assertTrue(key.value.matches(Regex("7/[0-9a-f-]{36}\\.jpg")))
        assertContentEquals(bytes, Files.readAllBytes(dir.resolve(key.value)))
    }

    @Test
    fun `같은 사용자도 저장할 때마다 다른 키를 받는다`() {
        val storage = LocalProfileImageStorage(dir.toString())

        val a = storage.store(UserId(7), byteArrayOf(1), ProfileImageType.PNG)
        val b = storage.store(UserId(7), byteArrayOf(1), ProfileImageType.PNG)

        assertNotEquals(a, b)
    }

    @Test
    fun `삭제하면 파일이 사라지고 없는 파일 삭제는 조용히 넘어간다`() {
        val storage = LocalProfileImageStorage(dir.toString())
        val key = storage.store(UserId(7), byteArrayOf(1), ProfileImageType.PNG)

        storage.delete(key)
        storage.delete(key)

        assertFalse(Files.exists(dir.resolve(key.value)))
    }

    @Test
    fun `저장소 밖을 가리키는 키는 삭제하지 않는다`() {
        val root = Files.createDirectories(dir.resolve("root"))
        val outside = Files.write(dir.resolve("outside.png"), byteArrayOf(1))
        val storage = LocalProfileImageStorage(root.toString())

        // ProfileImageKey.of 가 막는 ".." 키를 reconstitute 로 만들어 어댑터의 이중 방어만 시험한다.
        val e = assertFailsWith<IllegalStateException> {
            storage.delete(ProfileImageKey.reconstitute("a/../../outside.png"))
        }

        assertTrue(Files.exists(outside))
        assertEquals(true, e.message?.contains("저장소 밖"))
    }
}
