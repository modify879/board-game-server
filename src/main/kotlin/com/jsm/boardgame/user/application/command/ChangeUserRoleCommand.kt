package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.user.domain.model.UserRole

data class ChangeUserRoleCommand(val targetUserId: Long, val role: UserRole)
