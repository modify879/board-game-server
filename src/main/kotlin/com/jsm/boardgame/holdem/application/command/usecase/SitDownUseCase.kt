package com.jsm.boardgame.holdem.application.command.usecase

interface SitDownUseCase {
    fun sitDown(command: SitDownCommand): SitDownOutcome
}

data class SitDownCommand(val tableId: Long, val userId: Long, val seatNo: Int, val buyIn: Long, val postBlindImmediately: Boolean = false)

/** SEATED = 즉시 착석(핸드 없음). REQUESTED = 핸드 진행 중이라 참가 요청만 남김(다음 핸드 정산 뒤 처리). */
enum class SitDownOutcome { SEATED, REQUESTED }
