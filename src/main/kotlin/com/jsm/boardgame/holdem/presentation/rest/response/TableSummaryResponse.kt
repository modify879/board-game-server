package com.jsm.boardgame.holdem.presentation.rest.response

import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import java.time.Instant

/**
 * 로비 목록 한 줄. `handInProgress` 는 공개 뷰(`TablePublicView`)와 같은 뜻이다.
 * 바이인 범위는 요청한 사용자 기준이다 — 그 테이블의 재착석 의무를 반영한 미리보기이고(`obligationExpiresAt` 은 의무가 없으면 null),
 * 착석 시점의 `GET /tables/{id}/buy-in` 과 `POST seats` 검증이 최종이다.
 */
data class TableSummaryResponse(
    val tableId: Long,
    val name: String,
    val occupiedSeats: Int,
    val maxSeats: Int,
    val handInProgress: Boolean,
    val smallBlind: Long,
    val bigBlind: Long,
    val minBuyIn: Long,
    val maxBuyIn: Long,
    val obligationExpiresAt: Instant?,
) {
    companion object {
        fun from(view: TableSummaryView): TableSummaryResponse = TableSummaryResponse(
            view.tableId, view.name, view.occupiedSeats, view.maxSeats, view.handInProgress,
            view.smallBlind, view.bigBlind, view.minBuyIn, view.maxBuyIn, view.obligationExpiresAt,
        )
    }
}
