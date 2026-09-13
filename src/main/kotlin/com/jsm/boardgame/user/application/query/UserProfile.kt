package com.jsm.boardgame.user.application.query

/** 조회 전용 응답 DTO. 도메인 모델이 아니다. */
data class UserProfile(
    val id: Long,
    val username: String,
    val nickname: String,
    val profileImageKey: String?,
)
