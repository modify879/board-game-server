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

        /** 영속 복원 전용 — 검증하지 않는다. 사용자 입력에는 [of] 를 써라. */
        fun reconstitute(value: String): ProfileImageKey = ProfileImageKey(value)
    }
}
