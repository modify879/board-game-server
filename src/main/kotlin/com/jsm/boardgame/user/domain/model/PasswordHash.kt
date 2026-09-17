package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidPasswordException
import com.jsm.boardgame.user.domain.exception.UserErrorCode

@JvmInline
value class PasswordHash(val value: String) {

    init {
        if (value.isBlank()) {
            throw InvalidPasswordException(UserErrorCode.PASSWORD_TOO_SHORT, "비밀번호 해시가 비어 있습니다")
        }
    }

    override fun toString(): String = "PasswordHash(****)"
}
