package com.jsm.boardgame.holdem.application.command.usecase

interface UpdateSeatPresenceUseCase {
    fun update(command: UpdateSeatPresenceCommand)
}

data class UpdateSeatPresenceCommand(val userId: Long, val presence: String)
