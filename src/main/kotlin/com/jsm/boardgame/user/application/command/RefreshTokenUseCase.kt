package com.jsm.boardgame.user.application.command

interface RefreshTokenUseCase {
    fun refresh(command: RefreshTokenCommand): AuthTokens
}
