package com.jsm.boardgame.holdem.application.command.usecase

interface CancelJoinRequestUseCase {
    fun cancel(command: CancelJoinRequestCommand)
}

/** [tableId] 는 취소할 대기열 항목이 있는 테이블이다 — 사용자가 다른 테이블에 대기 중이면 없는 요청이다. */
data class CancelJoinRequestCommand(val tableId: Long, val userId: Long)
