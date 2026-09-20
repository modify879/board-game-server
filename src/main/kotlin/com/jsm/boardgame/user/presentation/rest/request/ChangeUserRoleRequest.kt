package com.jsm.boardgame.user.presentation.rest.request

import com.jsm.boardgame.user.application.command.usecase.ChangeUserRoleCommand
import com.jsm.boardgame.user.domain.model.UserRole

data class ChangeUserRoleRequest(val role: UserRole) {
    fun toCommand(targetUserId: Long): ChangeUserRoleCommand = ChangeUserRoleCommand(targetUserId, role)
}
