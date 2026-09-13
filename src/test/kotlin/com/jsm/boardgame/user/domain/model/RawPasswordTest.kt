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

    @Test
    fun `ASCII 72바이트 비밀번호는 통과한다`() {
        val password = "a".repeat(72)
        assertEquals(password, RawPassword.of(password).value)
    }

    @Test
    fun `ASCII 73바이트 비밀번호는 거부된다`() {
        val e = assertFailsWith<InvalidPasswordException> { RawPassword.of("a".repeat(73)) }
        assertEquals(UserErrorCode.PASSWORD_TOO_LONG, e.errorCode)
    }

    @Test
    fun `한글 24자(72바이트) 비밀번호는 통과한다`() {
        val password = "가".repeat(24)
        assertEquals(password, RawPassword.of(password).value)
    }

    @Test
    fun `한글 25자(75바이트) 비밀번호는 PASSWORD_TOO_LONG 으로 거부된다`() {
        val e = assertFailsWith<InvalidPasswordException> { RawPassword.of("가".repeat(25)) }
        assertEquals(UserErrorCode.PASSWORD_TOO_LONG, e.errorCode)
    }

    @Test
    fun `이모지 4개는 length 로는 8이지만 코드포인트로는 4개라 PASSWORD_TOO_SHORT 로 거부된다`() {
        val password = "🎲".repeat(4) // 🎲 서로게이트 페어 4개, String.length == 8, codePointCount == 4
        val e = assertFailsWith<InvalidPasswordException> { RawPassword.of(password) }
        assertEquals(UserErrorCode.PASSWORD_TOO_SHORT, e.errorCode)
    }
}
