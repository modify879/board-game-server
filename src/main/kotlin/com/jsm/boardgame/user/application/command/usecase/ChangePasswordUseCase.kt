package com.jsm.boardgame.user.application.command.usecase

interface ChangePasswordUseCase {
    /** 성공하면 기존 세션을 끝내고 새 토큰을 발급해 돌려준다. */
    fun changePassword(command: ChangePasswordCommand): AuthTokens
}

data class ChangePasswordCommand(
    val userId: Long,
    val currentPassword: String,
    val newPassword: String,
)
