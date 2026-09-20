package com.jsm.boardgame.user.application.command.usecase

import com.jsm.boardgame.user.domain.model.UserRole

interface ChangeUserRoleUseCase {
    fun changeRole(command: ChangeUserRoleCommand)
}

data class ChangeUserRoleCommand(val targetUserId: Long, val role: UserRole)
