package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.user.application.query.UserProfile

data class UserProfileResponse(
    val id: Long,
    val username: String,
    val nickname: String,
    val profileImageUrl: String,
) {
    companion object {
        fun from(profile: UserProfile, profileImageProperties: ProfileImageProperties): UserProfileResponse =
            UserProfileResponse(
                id = profile.id,
                username = profile.username,
                nickname = profile.nickname,
                profileImageUrl = resolveProfileImageUrl(profile.profileImageKey, profileImageProperties),
            )

        private fun resolveProfileImageUrl(key: String?, properties: ProfileImageProperties): String {
            if (key == null) return properties.defaultUrl
            val base = properties.baseUrl.trimEnd('/')
            val path = key.trimStart('/')
            return "$base/$path"
        }
    }
}
