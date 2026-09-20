package com.jsm.boardgame.user.application.command.usecase

interface RefreshTokenUseCase {
    fun refresh(command: RefreshTokenCommand): AuthTokens
}

data class RefreshTokenCommand(
    val refreshToken: String,
)
