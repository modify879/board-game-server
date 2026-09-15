package com.jsm.boardgame.user.application.command

import com.jsm.boardgame.user.application.port.IssuedTokens

interface RefreshTokenUseCase {
    fun refresh(command: RefreshTokenCommand): IssuedTokens
}
