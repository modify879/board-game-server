package com.jsm.boardgame.holdem.application.query.view

import java.time.Instant

/**
 * 로비 목록 한 줄. 어댑터는 앞 9개만 채워 온다 — `handInProgress` 는 false, 바이인은 정책 컬럼(블라인드 배수)이다.
 * 조회 서비스가 `handInProgress` 와 사용자별 [minBuyIn]·[maxBuyIn]·[obligationExpiresAt](재착석 의무 반영)을 덮어쓴다.
 */
data class TableSummaryView @JvmOverloads constructor(
    val tableId: Long,
    val name: String,
    val occupiedSeats: Int,
    val maxSeats: Int,
    val handInProgress: Boolean,
    val smallBlind: Long,
    val bigBlind: Long,
    val minBuyInBigBlinds: Int,
    val maxBuyInBigBlinds: Int,
    val minBuyIn: Long = 0L,
    val maxBuyIn: Long = 0L,
    val obligationExpiresAt: Instant? = null,
)
