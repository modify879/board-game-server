package com.jsm.boardgame.holdem.presentation.rest.request

import com.jsm.boardgame.holdem.application.command.usecase.SitDownCommand

data class SitDownRequest(val buyIn: Long, val postBlindImmediately: Boolean = false) {
    fun toCommand(tableId: Long, userId: Long): SitDownCommand =
        SitDownCommand(tableId = tableId, userId = userId, buyIn = buyIn, postBlindImmediately = postBlindImmediately)
}
