package com.jsm.boardgame.holdem.application.query.view

data class TableSummaryView(val tableId: Long, val name: String, val occupiedSeats: Int, val maxSeats: Int)
