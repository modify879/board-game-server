package com.jsm.boardgame.holdem.presentation.ws

/**
 * 홀덤 STOMP 목적지 상수와 파싱 헬퍼.
 * 공개 채널: /topic/tables/{tableId}. 개인 채널: /user/queue/tables/{tableId}
 * (클라이언트가 SUBSCRIBE 프레임에 실제로 보내는 목적지 문자열 — 스프링이 세션별 큐로 라우팅한다).
 */
object HoldemDestinations {

    private val TABLE_ID_PATTERN = Regex("""^/(?:topic|user/queue)/tables/(\d+)$""")

    fun publicTopicOf(tableId: Long): String = "/topic/tables/$tableId"

    fun privateQueueOf(tableId: Long): String = "/user/queue/tables/$tableId"

    /** 위 두 목적지 패턴에서 tableId 를 뽑는다. 홀덤 테이블 목적지가 아니면 null. */
    fun tableIdOf(destination: String): Long? =
        TABLE_ID_PATTERN.find(destination)?.groupValues?.get(1)?.toLongOrNull()
}
