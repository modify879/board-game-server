package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidUsernameException

@JvmInline
value class Username private constructor(val value: String) {

    companion object {
        private val PATTERN = Regex("^[a-z][a-z0-9_]{3,19}$")

        fun of(raw: String): Username {
            val normalized = raw.trim().lowercase()
            if (!PATTERN.matches(normalized)) {
                throw InvalidUsernameException("사용자명 형식이 올바르지 않습니다 (length=${raw.length})")
            }
            return Username(normalized)
        }

        /** 영속 복원 전용 — 검증하지 않는다. 사용자 입력에는 [of] 를 써라. */
        fun reconstitute(value: String): Username = Username(value)
    }
}
