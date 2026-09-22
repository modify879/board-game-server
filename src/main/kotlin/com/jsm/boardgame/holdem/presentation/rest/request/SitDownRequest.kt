package com.jsm.boardgame.holdem.presentation.rest.request

import com.jsm.boardgame.holdem.application.command.usecase.SitDownCommand

data class SitDownRequest(val seatNo: Int, val buyIn: Long) {
    fun toCommand(tableId: Long, userId: Long): SitDownCommand =
        SitDownCommand(tableId = tableId, userId = userId, seatNo = seatNo, buyIn = buyIn)
}
