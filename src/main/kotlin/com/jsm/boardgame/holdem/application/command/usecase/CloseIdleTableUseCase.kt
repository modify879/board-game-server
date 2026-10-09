package com.jsm.boardgame.holdem.application.command.usecase

interface CloseIdleTableUseCase {
    /** 닫았으면 true. 이미 없거나 더는 한가하지 않으면(착석자·핸드·대기열) 아무것도 하지 않고 false. */
    fun close(command: CloseIdleTableCommand): Boolean
}

data class CloseIdleTableCommand(val tableId: Long)
