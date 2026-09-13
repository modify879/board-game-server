package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.user.application.command.LoginCommand

data class LoginRequest(
    val username: String,
    val password: String,
) {
    fun toCommand(): LoginCommand = LoginCommand(username = username, password = password)
}
