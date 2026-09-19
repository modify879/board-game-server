package com.jsm.boardgame.user.presentation.rest.request

import com.jsm.boardgame.user.application.command.SignUpCommand
import com.jsm.boardgame.user.presentation.exception.PasswordConfirmMismatchException

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
