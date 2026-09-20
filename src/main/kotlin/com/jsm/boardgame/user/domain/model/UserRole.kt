package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidUserRoleException

enum class UserRole {
    USER,
    ADMIN,
    ;

    companion object {
        /** 경계를 넘어온 문자열을 역할로 바꾼다. 알 수 없는 값은 USER_ROLE_INVALID 로 떨어진다. */
        fun of(raw: String): UserRole =
            entries.find { it.name == raw.trim().uppercase() }
                ?: throw InvalidUserRoleException("알 수 없는 역할: role=$raw")
    }
}
