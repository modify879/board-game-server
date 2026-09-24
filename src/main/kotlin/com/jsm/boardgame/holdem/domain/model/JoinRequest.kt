package com.jsm.boardgame.holdem.domain.model

import java.time.Instant

/** 핸드 진행 중 들어온 착석 요청. 그 핸드가 끝나면 HandSettler 가 요청 순서(requestedAt)대로 처리한다. */
data class JoinRequest(
    val userId: Long,
    val seatNo: Int,
    val buyIn: Chips,
    val postBlindImmediately: Boolean,
    val requestedAt: Instant,
)
