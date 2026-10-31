package com.jsm.boardgame.holdem.application.command.usecase

interface CreateTableUseCase {
    /** 만든 테이블의 id. */
    fun create(command: CreateTableCommand): Long
}

data class CreateTableCommand(val ownerUserId: Long, val name: String, val buyIn: Long)
