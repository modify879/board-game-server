package com.jsm.boardgame.holdem.application.port

/** STOMP 세션 존재 여부 — 대기열 입장 조건 */
interface UserConnections {
    fun isConnected(userId: Long): Boolean
}
