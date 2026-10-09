package com.jsm.boardgame.user.application.query.view

import java.time.Instant

data class UserSummary(
    val id: Long,
    val username: String,
    val nickname: String,
    val role: String,
    val lockedAt: Instant?,
)
