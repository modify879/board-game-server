package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidNicknameException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NicknameTest {

    @Test
    fun `한글 영문 숫자 공백이 섞인 닉네임이 통과한다`() {
        assertEquals("길동이", Nickname.of("길동이").value)
        assertEquals("gildong1", Nickname.of("gildong1").value)
        assertEquals("길동 1번", Nickname.of("길동 1번").value)
    }

    @Test
    fun `NFC 정규화로 분해형과 완성형 닉네임의 값이 같다`() {
        // decomposed: 'e' + combining acute accent(U+0301) = codePointCount 13 (정규화 전)
        val decomposed = "abcdefghijk" + "é"
        // precomposed: 이미 결합된 단일 코드포인트 é(U+00E9) = codePointCount 12
        val precomposed = "abcdefghijké"

        val fromDecomposed = Nickname.of(decomposed)
        val fromPrecomposed = Nickname.of(precomposed)

        // decomposed 는 정규화 없이 세면 13자라 길이 상한(12)을 넘지만,
        // NFC 정규화가 길이 검사보다 먼저 일어나므로 정상 통과하고 값도 완성형과 같아야 한다.
        assertEquals("abcdefghijké", fromDecomposed.value)
        assertEquals(fromPrecomposed.value, fromDecomposed.value)
    }

    @Test
    fun `앞뒤 공백은 제거된다`() {
        assertEquals("홍길동", Nickname.of(" 홍길동 ").value)
    }

    @Test
    fun `연속된 공백은 하나로 축약된다`() {
        assertEquals("홍 길동", Nickname.of("홍  길동").value)
    }

    @Test
    fun `제로폭 문자가 포함되면 거부된다`() {
        val e = assertFailsWith<InvalidNicknameException> { Nickname.of("ad​min") }
        assertEquals(UserErrorCode.NICKNAME_FORBIDDEN_CHARACTER, e.errorCode)
    }

    @Test
    fun `양방향 제어 문자가 포함되면 거부된다`() {
        val e = assertFailsWith<InvalidNicknameException> { Nickname.of("‮admin") }
        assertEquals(UserErrorCode.NICKNAME_FORBIDDEN_CHARACTER, e.errorCode)
    }

    @Test
    fun `제어 문자가 포함되면 거부된다`() {
        val e = assertFailsWith<InvalidNicknameException> { Nickname.of("admin") }
        assertEquals(UserErrorCode.NICKNAME_FORBIDDEN_CHARACTER, e.errorCode)
    }

    @Test
    fun `빈 문자열은 NICKNAME_BLANK 로 거부된다`() {
        val e = assertFailsWith<InvalidNicknameException> { Nickname.of("") }
        assertEquals(UserErrorCode.NICKNAME_BLANK, e.errorCode)
    }

    @Test
    fun `공백만 있으면 NICKNAME_BLANK 로 거부된다`() {
        val e = assertFailsWith<InvalidNicknameException> { Nickname.of("   ") }
        assertEquals(UserErrorCode.NICKNAME_BLANK, e.errorCode)
    }

    @Test
    fun `길이 하한 미만이면 NICKNAME_LENGTH 로 거부된다`() {
        val e = assertFailsWith<InvalidNicknameException> { Nickname.of("a") }
        assertEquals(UserErrorCode.NICKNAME_LENGTH, e.errorCode)
    }

    @Test
    fun `길이 상한을 넘으면 NICKNAME_LENGTH 로 거부된다`() {
        val e = assertFailsWith<InvalidNicknameException> { Nickname.of("abcdefghijklm") }
        assertEquals(UserErrorCode.NICKNAME_LENGTH, e.errorCode)
    }

    @Test
    fun `길이 경계값 2자와 12자는 통과한다`() {
        assertEquals("ab", Nickname.of("ab").value)
        assertEquals("abcdefghijkl", Nickname.of("abcdefghijkl").value)
    }

    @Test
    fun `BMP 밖 이모지는 한 글자로 계산된다`() {
        val dice = "🎲" // U+1F3B2, surrogate pair (String.length 로는 2)

        val twelve = dice.repeat(12)
        assertEquals(twelve, Nickname.of(twelve).value)

        val thirteen = dice.repeat(13)
        val e = assertFailsWith<InvalidNicknameException> { Nickname.of(thirteen) }
        assertEquals(UserErrorCode.NICKNAME_LENGTH, e.errorCode)
    }

    @Test
    fun `제로폭 문자만 있으면 길이가 아니라 금지 문자로 거부된다`() {
        // codePointCount 는 1 이라 길이 검사가 먼저였다면 NICKNAME_LENGTH 여야 하지만,
        // 금지 문자 검사가 길이 검사보다 먼저 실행되므로 NICKNAME_FORBIDDEN_CHARACTER 여야 한다.
        val e = assertFailsWith<InvalidNicknameException> { Nickname.of("​") }
        assertEquals(UserErrorCode.NICKNAME_FORBIDDEN_CHARACTER, e.errorCode)
    }

    @Test
    fun `restore 는 현재 규칙을 위반하는 값도 예외 없이 복원하고, of 는 같은 값을 거부한다`() {
        val tooLong = "현재규칙을위반하는아주긴닉네임" // 15자 — 현재 상한(12자)을 넘는다

        assertEquals(tooLong, Nickname.restore(tooLong).value)

        val e = assertFailsWith<InvalidNicknameException> { Nickname.of(tooLong) }
        assertEquals(UserErrorCode.NICKNAME_LENGTH, e.errorCode)
    }
}
