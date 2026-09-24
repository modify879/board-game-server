package com.jsm.boardgame.user.application.command.usecase

interface UnlockUserUseCase {
    fun unlock(command: UnlockUserCommand)
}

data class UnlockUserCommand(val targetUserId: Long)
