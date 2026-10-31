package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.UnsupportedProfileImageTypeException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProfileImageTypeTest {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0)
    private val webp = "RIFF".toByteArray() + byteArrayOf(1, 2, 3, 4) + "WEBP".toByteArray() + byteArrayOf(0)

    @Test
    fun `PNG JPEG WEBP 를 매직 바이트로 알아본다`() {
        assertEquals(ProfileImageType.PNG, ProfileImageType.detect(png))
        assertEquals(ProfileImageType.JPEG, ProfileImageType.detect(jpeg))
        assertEquals(ProfileImageType.WEBP, ProfileImageType.detect(webp))
    }

    @Test
    fun `확장자는 판별된 형식에서 나온다`() {
        assertEquals("png", ProfileImageType.PNG.extension)
        assertEquals("jpg", ProfileImageType.JPEG.extension)
        assertEquals("webp", ProfileImageType.WEBP.extension)
    }

    @Test
    fun `알 수 없는 내용 빈 배열 짧은 배열은 UNSUPPORTED_TYPE 이다`() {
        val garbage = listOf(
            "hello world".toByteArray(),
            ByteArray(0),
            byteArrayOf(0x89.toByte(), 0x50),
            "RIFF".toByteArray() + byteArrayOf(1, 2, 3, 4) + "WAVE".toByteArray(),
            "RIFFWEBP".toByteArray(),
        )
        garbage.forEach {
            val e = assertFailsWith<UnsupportedProfileImageTypeException> { ProfileImageType.detect(it) }
            assertEquals(UserErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE, e.errorCode)
        }
    }
}
