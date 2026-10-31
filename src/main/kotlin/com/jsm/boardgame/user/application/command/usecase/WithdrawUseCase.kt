package com.jsm.boardgame.user.application.command.usecase

interface WithdrawUseCase {
    fun withdraw(command: WithdrawCommand)
}

data class WithdrawCommand(val userId: Long, val password: String)
