package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidUsernameException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UsernameTest {

    @Test
    fun `정상적인 사용자명이 통과한다`() {
        assertEquals("user_01", Username.of("user_01").value)
    }

    @Test
    fun `대문자는 소문자로 정규화된다`() {
        assertEquals("admin1", Username.of("Admin1").value)
    }

    @Test
    fun `앞뒤 공백은 제거된다`() {
        assertEquals("user_01", Username.of(" user_01 ").value)
    }

    @Test
    fun `첫 글자가 숫자면 거부된다`() {
        val e = assertFailsWith<InvalidUsernameException> { Username.of("1admin") }
        assertEquals(UserErrorCode.USERNAME_FORMAT, e.errorCode)
    }

    @Test
    fun `첫 글자가 밑줄이면 거부된다`() {
        val e = assertFailsWith<InvalidUsernameException> { Username.of("_admin") }
        assertEquals(UserErrorCode.USERNAME_FORMAT, e.errorCode)
    }

    @Test
    fun `한글이 포함되면 거부된다`() {
        val e = assertFailsWith<InvalidUsernameException> { Username.of("유저123") }
        assertEquals(UserErrorCode.USERNAME_FORMAT, e.errorCode)
    }

    @Test
    fun `공백이 포함되면 거부된다`() {
        val e = assertFailsWith<InvalidUsernameException> { Username.of("user 123") }
        assertEquals(UserErrorCode.USERNAME_FORMAT, e.errorCode)
    }

    @Test
    fun `하이픈이 포함되면 거부된다`() {
        val e = assertFailsWith<InvalidUsernameException> { Username.of("user-123") }
        assertEquals(UserErrorCode.USERNAME_FORMAT, e.errorCode)
    }

    @Test
    fun `마침표가 포함되면 거부된다`() {
        val e = assertFailsWith<InvalidUsernameException> { Username.of("user.123") }
        assertEquals(UserErrorCode.USERNAME_FORMAT, e.errorCode)
    }

    @Test
    fun `길이가 3자면 거부된다`() {
        val e = assertFailsWith<InvalidUsernameException> { Username.of("abc") }
        assertEquals(UserErrorCode.USERNAME_FORMAT, e.errorCode)
    }

    @Test
    fun `길이가 4자면 통과한다`() {
        assertEquals("abcd", Username.of("abcd").value)
    }

    @Test
    fun `길이가 20자면 통과한다`() {
        val name = "a".repeat(20)
        assertEquals(name, Username.of(name).value)
    }

    @Test
    fun `길이가 21자면 거부된다`() {
        val name = "a".repeat(21)
        val e = assertFailsWith<InvalidUsernameException> { Username.of(name) }
        assertEquals(UserErrorCode.USERNAME_FORMAT, e.errorCode)
    }
}
