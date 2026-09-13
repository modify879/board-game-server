package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidPasswordException

/**
 * Username/Nickname/RawPassword 와 달리 입력을 정규화할 필요가 없다 — 이미
 * PasswordHasher 포트가 만들어낸 해시 값이므로, 빈 값만 거르면 된다. 그래서
 * private 생성자 + 팩토리 대신 일반 생성자 + `init` 검증으로 충분하다.
 */
@JvmInline
value class PasswordHash(val value: String) {

    init {
        if (value.isBlank()) {
            throw InvalidPasswordException("비밀번호 해시가 비어 있습니다")
        }
    }

    override fun toString(): String = "PasswordHash(****)"
}
