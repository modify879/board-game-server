package com.jsm.boardgame.user.presentation.rest.response

import com.jsm.boardgame.user.application.query.view.UserSummary

data class UserSummaryResponse(
    val id: Long,
    val username: String,
    val nickname: String,
    val role: String,
    val locked: Boolean,
) {
    companion object {
        fun from(view: UserSummary): UserSummaryResponse =
            UserSummaryResponse(view.id, view.username, view.nickname, view.role, view.lockedAt != null)
    }
}
