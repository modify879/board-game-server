package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.UserId

/** STOMP 세션 존재 여부 — 대기열 입장 조건 */
interface UserConnections {
    fun isConnected(userId: UserId): Boolean

    /**
     * 끊긴 세션을 뺀 나머지가 있는지 — SessionDisconnectEvent 시점에 레지스트리가 그 세션을
     * 아직 들고 있을 수 있어 명시적으로 뺀다.
     */
    fun hasOtherSession(userId: UserId, excludingSessionId: String): Boolean
}
