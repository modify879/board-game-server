package com.jsm.boardgame.holdem.presentation.rest.request

import com.jsm.boardgame.holdem.application.command.usecase.RevealHandCommand

data class RevealRequest(val action: String) {
    fun toCommand(tableId: Long, userId: Long): RevealHandCommand =
        RevealHandCommand(tableId = tableId, userId = userId, action = action)
}
