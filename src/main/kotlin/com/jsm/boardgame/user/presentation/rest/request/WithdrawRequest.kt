package com.jsm.boardgame.user.presentation.rest.request

import com.jsm.boardgame.user.application.command.usecase.WithdrawCommand

data class WithdrawRequest(val password: String) {
    fun toCommand(userId: Long): WithdrawCommand = WithdrawCommand(userId, password)
}
