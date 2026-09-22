package com.jsm.boardgame.holdem.presentation.rest.response

import com.jsm.boardgame.holdem.domain.model.TableId

data class TableCreatedResponse(val tableId: Long) {
    companion object {
        fun from(tableId: TableId): TableCreatedResponse = TableCreatedResponse(tableId.value)
    }
}
