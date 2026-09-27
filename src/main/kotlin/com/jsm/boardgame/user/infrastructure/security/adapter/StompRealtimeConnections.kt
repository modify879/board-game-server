package com.jsm.boardgame.user.infrastructure.security.adapter

import com.jsm.boardgame.common.web.StompSessionRegistry
import com.jsm.boardgame.user.application.port.RealtimeConnections
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * [RealtimeConnections] 을 STOMP 세션 레지스트리로 구현한다.
 *
 * ROTATION_GRACE: 리프레시 성공 직후 블랙리스트에 오른 "직전" 액세스 토큰에 주는 유예. 클라이언트는
 * REST 갱신 응답을 받는 즉시 새 토큰을 인밴드로(/app/auth/refresh) 보내 소켓을 이어간다 — 그
 * 왕복 시간을 벌어 준다. 로그아웃·역할변경·재사용 탐지 등 다른 폐기 경로는 클라이언트가 이어갈
 * 이유가 없으므로 유예 없이 즉시 닫는다.
 */
@Component
class StompRealtimeConnections(
    private val stompSessionRegistry: StompSessionRegistry,
) : RealtimeConnections {

    override fun closeAllOf(userId: Long) {
        stompSessionRegistry.closeAllOf(userId)
    }

    override fun closeAfterGrace(accessTokenId: String) {
        stompSessionRegistry.closeAfterGrace(accessTokenId, ROTATION_GRACE)
    }

    companion object {
        private val ROTATION_GRACE: Duration = Duration.ofSeconds(30)
    }
}
