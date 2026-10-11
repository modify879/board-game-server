package com.jsm.boardgame.holdem.presentation.rest.request

import com.jsm.boardgame.holdem.application.command.usecase.AddChipsCommand

data class AddChipsRequest(val amount: Long) {
    fun toCommand(userId: Long): AddChipsCommand = AddChipsCommand(userId = userId, amount = amount)
}
