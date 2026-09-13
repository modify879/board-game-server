package com.jsm.boardgame.user.application.command

data class LoginCommand(
    val username: String,
    val password: String,
)
