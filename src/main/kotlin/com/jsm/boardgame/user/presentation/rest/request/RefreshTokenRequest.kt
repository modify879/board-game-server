package com.jsm.boardgame.user.presentation.rest.request

import com.jsm.boardgame.user.application.command.RefreshTokenCommand

data class RefreshTokenRequest(
    val refreshToken: String,
) {
    fun toCommand(): RefreshTokenCommand = RefreshTokenCommand(refreshToken = refreshToken)
}
