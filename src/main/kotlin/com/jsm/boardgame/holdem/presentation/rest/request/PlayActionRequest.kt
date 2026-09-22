package com.jsm.boardgame.holdem.presentation.rest.request

import com.jsm.boardgame.holdem.application.command.usecase.PlayActionCommand

data class PlayActionRequest(val action: String, val raiseToAmount: Long?) {
    fun toCommand(tableId: Long, userId: Long): PlayActionCommand =
        PlayActionCommand(tableId = tableId, userId = userId, action = action, raiseToAmount = raiseToAmount)
}
