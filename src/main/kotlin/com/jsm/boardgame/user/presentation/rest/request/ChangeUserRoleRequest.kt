package com.jsm.boardgame.user.presentation.rest.request

import com.jsm.boardgame.user.application.command.usecase.ChangeUserRoleCommand

data class ChangeUserRoleRequest(val role: String) {
    fun toCommand(targetUserId: Long): ChangeUserRoleCommand = ChangeUserRoleCommand(targetUserId, role)
}
