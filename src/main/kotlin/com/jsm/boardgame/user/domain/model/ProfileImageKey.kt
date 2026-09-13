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
    }
}
