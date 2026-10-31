package com.jsm.boardgame.holdem.presentation.rest.response

data class TableCreatedResponse(val tableId: Long) {
    companion object {
        fun from(tableId: Long): TableCreatedResponse = TableCreatedResponse(tableId)
    }
}
