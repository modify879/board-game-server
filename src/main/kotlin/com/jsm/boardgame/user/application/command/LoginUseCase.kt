package com.jsm.boardgame.user.application.command

interface LoginUseCase {
    fun login(command: LoginCommand): AuthTokens
}
