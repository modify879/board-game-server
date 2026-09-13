package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidProfileImageKeyException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProfileImageKeyTest {

    @Test
    fun `정상적인 키는 통과한다`() {
        assertEquals("avatars/123/profile.png", ProfileImageKey.of("avatars/123/profile.png").value)
    }

    @Test
    fun `상위 경로 참조가 포함되면 거부된다`() {
        val e = assertFailsWith<InvalidProfileImageKeyException> { ProfileImageKey.of("../etc/passwd") }
        assertEquals(UserErrorCode.PROFILE_IMAGE_KEY_INVALID, e.errorCode)
    }

    @Test
    fun `슬래시로 시작하면 거부된다`() {
        val e = assertFailsWith<InvalidProfileImageKeyException> { ProfileImageKey.of("/etc/passwd") }
        assertEquals(UserErrorCode.PROFILE_IMAGE_KEY_INVALID, e.errorCode)
    }

    @Test
    fun `빈 값이면 거부된다`() {
        val e = assertFailsWith<InvalidProfileImageKeyException> { ProfileImageKey.of("") }
        assertEquals(UserErrorCode.PROFILE_IMAGE_KEY_INVALID, e.errorCode)
    }

    @Test
    fun `공백만 있으면 거부된다`() {
        val e = assertFailsWith<InvalidProfileImageKeyException> { ProfileImageKey.of("   ") }
        assertEquals(UserErrorCode.PROFILE_IMAGE_KEY_INVALID, e.errorCode)
    }
}
