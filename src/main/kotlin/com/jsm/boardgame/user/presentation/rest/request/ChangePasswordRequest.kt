package com.jsm.boardgame.user.presentation.rest.request

import com.jsm.boardgame.user.application.command.usecase.ChangePasswordCommand
import com.jsm.boardgame.user.presentation.exception.PasswordConfirmMismatchException

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
    val newPasswordConfirm: String,
) {
    fun toCommand(userId: Long): ChangePasswordCommand {
        if (newPassword != newPasswordConfirm) {
            throw PasswordConfirmMismatchException("password confirmation does not match")
        }
        return ChangePasswordCommand(userId, currentPassword, newPassword)
    }
}
