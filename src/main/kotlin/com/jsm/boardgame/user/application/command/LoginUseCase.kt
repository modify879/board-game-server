package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.user.application.port.IssuedTokens

interface LoginUseCase {
    fun login(command: LoginCommand): IssuedTokens
}
