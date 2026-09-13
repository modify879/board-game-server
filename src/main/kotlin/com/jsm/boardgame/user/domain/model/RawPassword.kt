package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidPasswordException

@JvmInline
value class RawPassword private constructor(val value: String) {

    override fun toString(): String = "RawPassword(****)"

    companion object {
        private const val MIN_LENGTH = 8

        fun of(raw: String): RawPassword {
            if (raw.length < MIN_LENGTH) {
                throw InvalidPasswordException("비밀번호가 너무 짧습니다 (length=${raw.length})")
            }
            return RawPassword(raw)
        }
    }
}
