package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidPasswordException
import com.jsm.boardgame.user.domain.exception.UserErrorCode

@JvmInline
value class RawPassword private constructor(val value: String) {

    override fun toString(): String = "RawPassword(****)"

    companion object {
        private const val MIN_LENGTH = 8 // 코드포인트 수

        // BCrypt 해시 알고리즘의 입력 상한과 맞춘 값이다. BCryptPasswordEncoder.encode() 는
        // UTF-8 로 72바이트를 넘는 입력을 받으면 IllegalArgumentException 을 던진다.
        // 이 값을 올리면 여기를 통과한 비밀번호가 해싱 시점(BCryptPasswordHasher.hash())에서
        // 예외를 내며 500 으로 새어나간다 — 올리지 말 것.
        private const val MAX_BYTES = 72 // UTF-8 바이트 수

        fun of(raw: String): RawPassword {
            if (raw.codePointCount(0, raw.length) < MIN_LENGTH) {
                throw InvalidPasswordException(
                    UserErrorCode.PASSWORD_TOO_SHORT,
                    "비밀번호가 너무 짧습니다 (length=${raw.length})",
                )
            }

            val byteLength = raw.toByteArray(Charsets.UTF_8).size
            if (byteLength > MAX_BYTES) {
                throw InvalidPasswordException(
                    UserErrorCode.PASSWORD_TOO_LONG,
                    "비밀번호가 너무 깁니다 (bytes=$byteLength)",
                )
            }

            return RawPassword(raw)
        }
    }
}
