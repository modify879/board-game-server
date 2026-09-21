package com.jsm.boardgame.holdem.application.command.usecase

import com.jsm.boardgame.holdem.domain.model.TableId

interface CreateTableUseCase {
    fun create(command: CreateTableCommand): TableId
}

data class CreateTableCommand(val ownerUserId: Long, val name: String)
