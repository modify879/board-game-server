package com.jsm.boardgame.holdem.presentation.rest.response

import com.jsm.boardgame.holdem.application.query.view.TableSummaryView

data class TableSummaryResponse(val tableId: Long, val name: String, val occupiedSeats: Int, val maxSeats: Int) {
    companion object {
        fun from(view: TableSummaryView): TableSummaryResponse =
            TableSummaryResponse(view.tableId, view.name, view.occupiedSeats, view.maxSeats)
    }
}
