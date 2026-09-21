package com.jsm.boardgame.holdem.presentation.rest.request

import com.jsm.boardgame.holdem.application.command.usecase.CreateTableCommand

data class CreateTableRequest(val name: String) {
    fun toCommand(ownerUserId: Long): CreateTableCommand = CreateTableCommand(ownerUserId = ownerUserId, name = name)
}
