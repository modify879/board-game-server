package com.jsm.boardgame.user.presentation.rest.response

import com.jsm.boardgame.user.application.query.UserProfile

data class UserProfileResponse(
    val id: Long,
    val username: String,
    val nickname: String,
    val profileImageUrl: String,
) {
    companion object {
        fun from(profile: UserProfile, profileImageUrl: String): UserProfileResponse =
            UserProfileResponse(
                id = profile.id,
                username = profile.username,
                nickname = profile.nickname,
                profileImageUrl = profileImageUrl,
            )
    }
}
