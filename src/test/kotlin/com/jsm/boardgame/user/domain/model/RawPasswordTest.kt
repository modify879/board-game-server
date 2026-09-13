package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidPasswordException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RawPasswordTest {

    @Test
    fun `7자 비밀번호는 거부된다`() {
        val e = assertFailsWith<InvalidPasswordException> { RawPassword.of("1234567") }
        assertEquals(UserErrorCode.PASSWORD_TOO_SHORT, e.errorCode)
    }

    @Test
    fun `8자 비밀번호는 통과한다`() {
        assertEquals("password", RawPassword.of("password").value)
    }

    @Test
    fun `toString 은 원본 비밀번호를 노출하지 않는다`() {
        val raw = RawPassword.of("supersecret")
        assertFalse(raw.toString().contains("supersecret"))
    }

    @Test
    fun `PasswordHash 의 toString 도 원본 값을 노출하지 않는다`() {
        val hash = PasswordHash("supersecrethash")
        assertFalse(hash.toString().contains("supersecrethash"))
    }
}
