package com.jsm.boardgame.user.application.query.view

data class UserProfile(
    val id: Long,
    val username: String,
    val nickname: String,
    val profileImageKey: String?,
)
