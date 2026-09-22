package com.jsm.boardgame.holdem.presentation.rest.response

import com.jsm.boardgame.holdem.application.query.view.MySeatView

data class MySeatResponse(val tableId: Long, val seatNo: Int, val handInProgress: Boolean) {
    companion object {
        fun from(view: MySeatView): MySeatResponse = MySeatResponse(view.tableId, view.seatNo, view.handInProgress)
    }
}
