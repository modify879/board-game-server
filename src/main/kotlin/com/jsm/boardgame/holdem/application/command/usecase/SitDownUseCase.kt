package com.jsm.boardgame.holdem.application.command.usecase

interface SitDownUseCase {
    /** 1-based 대기열 순번을 돌려준다. */
    fun sitDown(command: SitDownCommand): Int
}

data class SitDownCommand(val tableId: Long, val userId: Long, val buyIn: Long, val postBlindImmediately: Boolean = false)
