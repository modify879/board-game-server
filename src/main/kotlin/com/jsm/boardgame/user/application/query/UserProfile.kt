package com.jsm.boardgame.user.application.query

data class UserProfile(
    val id: Long,
    val username: String,
    val nickname: String,
    val profileImageKey: String?,
)
