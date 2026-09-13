package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.user.application.command.SignUpCommand

data class SignUpRequest(
    val username: String,
    val password: String,
    val passwordConfirm: String,
    val nickname: String,
) {
    fun toCommand(): SignUpCommand {
        if (password != passwordConfirm) {
            throw PasswordConfirmMismatchException("password confirmation does not match")
        }
        return SignUpCommand(username = username, password = password, nickname = nickname)
    }
}
