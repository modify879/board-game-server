package com.jsm.boardgame.holdem.presentation.rest.response

import com.jsm.boardgame.holdem.application.query.view.TableSummaryView

/** 로비 목록 한 줄. `handInProgress` 는 공개 뷰(`TablePublicView`)와 같은 뜻이다. */
data class TableSummaryResponse(
    val tableId: Long,
    val name: String,
    val occupiedSeats: Int,
    val maxSeats: Int,
    val handInProgress: Boolean,
) {
    companion object {
        fun from(view: TableSummaryView): TableSummaryResponse =
            TableSummaryResponse(view.tableId, view.name, view.occupiedSeats, view.maxSeats, view.handInProgress)
    }
}
