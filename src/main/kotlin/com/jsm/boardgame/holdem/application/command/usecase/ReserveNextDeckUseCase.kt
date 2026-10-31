package com.jsm.boardgame.holdem.application.command.usecase

interface ReserveNextDeckUseCase {
    /** 카드 표기가 틀리면 CARD_NOTATION_INVALID, 사양이 잘못되면 [IllegalArgumentException]. */
    fun reserve(command: ReserveNextDeckCommand)
}

data class ReserveNextDeckCommand(val tableId: Long, val holeCards: Map<Int, List<String>>, val board: List<String>)
