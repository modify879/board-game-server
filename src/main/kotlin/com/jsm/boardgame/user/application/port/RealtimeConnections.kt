package com.jsm.boardgame.user.application.port

/**
 * 로그아웃·역할변경·리프레시 재사용 탐지처럼 액세스 토큰을 블랙리스트에 올릴 때, 이미 열려 있는
 * 실시간(STOMP) 세션도 함께 정리하기 위한 출력 포트. STOMP 는 common/web 인프라라 user 의
 * domain·application 이 직접 참조할 수 없다 — infrastructure 의 어댑터가 이 포트를 구현해
 * 그 인프라를 부른다.
 */
interface RealtimeConnections {
    /**
     * 그 사용자로 연결된 세션을 전부 즉시 닫는다. 단일 기기 정책이라 즉시 폐기는 "이 사용자의
     * 연결 전부"다 — jti 로 찾으면 인밴드 갱신과 경합해 놓친다(세션이 이미 새 토큰으로
     * 옮겨간 뒤라 옛 jti 로는 못 찾는다).
     */
    fun closeAllOf(userId: Long)

    /**
     * 그 액세스 토큰으로 연결된 세션을 유예를 두고 닫는다 — 리프레시 성공 직후처럼 클라이언트가
     * REST 로 이미 새 토큰을 받아 곧바로 인밴드로 갱신을 보낼 시간을 준다.
     */
    fun closeAfterGrace(accessTokenId: String)
}
