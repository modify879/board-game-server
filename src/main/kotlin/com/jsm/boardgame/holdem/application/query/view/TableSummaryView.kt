package com.jsm.boardgame.holdem.application.query.view

/** 로비 목록 한 줄. `handInProgress` 는 어댑터가 false 로 채워 오고 조회 서비스가 HandStore 로 덮어쓴다. */
data class TableSummaryView(val tableId: Long, val name: String, val occupiedSeats: Int, val maxSeats: Int, val handInProgress: Boolean)
