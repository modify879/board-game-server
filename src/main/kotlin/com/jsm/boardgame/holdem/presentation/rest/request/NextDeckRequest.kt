package com.jsm.boardgame.holdem.presentation.rest.request

data class NextDeckRequest(
    val holeCards: Map<Int, List<String>> = emptyMap(),
    val board: List<String> = emptyList(),
)
