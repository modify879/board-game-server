package com.jsm.boardgame.user.domain.model

import com.jsm.boardgame.user.domain.exception.InvalidProfileImageKeyException

@JvmInline
value class ProfileImageKey private constructor(val value: String) {

    companion object {
        fun of(raw: String): ProfileImageKey {
            val trimmed = raw.trim()
            if (trimmed.isBlank() || trimmed.contains("..") || trimmed.startsWith("/")) {
                throw InvalidProfileImageKeyException("프로필 이미지 키가 유효하지 않습니다")
            }
            return ProfileImageKey(trimmed)
        }

        /**
         * 영속 계층 복원 전용. 저장 시점에 이미 검증을 통과한 값이므로
         * 현재 규칙을 다시 적용하지 않는다. 규칙이 조여져도 기존 데이터를 읽을 수 있어야 한다.
         * 사용자 입력에는 절대 쓰지 마라 — [of] 를 써라.
         */
        fun restore(value: String): ProfileImageKey = ProfileImageKey(value)
    }
}
