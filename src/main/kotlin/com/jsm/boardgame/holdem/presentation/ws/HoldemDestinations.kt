package com.jsm.boardgame.holdem.presentation.ws

/**
 * 홀덤 STOMP 목적지 상수와 파싱 헬퍼.
 * 공개 채널: /topic/tables/{tableId}. 개인 채널: /user/queue/tables/{tableId}
 * (클라이언트가 SUBSCRIBE 프레임에 실제로 보내는 목적지 문자열 — 스프링이 세션별 큐로 라우팅한다).
 */
object HoldemDestinations {

    private const val TOPIC_PREFIX = "/topic/tables/"
    private const val PRIVATE_QUEUE_PREFIX = "/user/queue/tables/"

    private val TABLE_ID_PATTERN = Regex("""^/(?:topic|user/queue)/tables/(\d+)$""")

    fun publicTopicOf(tableId: Long): String = "$TOPIC_PREFIX$tableId"

    fun privateQueueOf(tableId: Long): String = "$PRIVATE_QUEUE_PREFIX$tableId"

    /**
     * convertAndSendToUser 에 넘길 목적지. 그 메서드는 "/user" 프리픽스를 스스로 붙이므로,
     * 클라이언트가 SUBSCRIBE 에 쓰는 전체 경로([privateQueueOf], 이미 "/user" 가 붙어 있다)를
     * 그대로 넘기면 "/user/{id}/user/queue/..." 로 이중으로 붙는다.
     */
    fun privateQueueSendTargetOf(tableId: Long): String = privateQueueOf(tableId).removePrefix("/user")

    /** 위 두 목적지 패턴에서 tableId 를 뽑는다. 홀덤 테이블 목적지가 아니면 null. */
    fun tableIdOf(destination: String): Long? =
        TABLE_ID_PATTERN.find(destination)?.groupValues?.get(1)?.toLongOrNull()

    /** 목적지가 개인 큐(/user/queue/tables/{id})인지. 공개 토픽과 개인 큐를 인가 정책에서 가르는 데 쓴다. */
    fun isPrivateQueue(destination: String): Boolean = destination.startsWith(PRIVATE_QUEUE_PREFIX)

    /**
     * 목적지가 홀덤 테이블 구독처럼 보이는지 — tableId 파싱 성공 여부와 무관하다.
     * 인터셉터가 "홀덤 목적지인데 형식이 깨졌다(오버플로·와일드카드·꼬리 세그먼트 등)"와
     * "애초에 홀덤 목적지가 아니다"를 구분해 전자만 거부(fail-closed)하는 데 쓴다.
     */
    fun looksLikeTableDestination(destination: String): Boolean =
        destination.startsWith(TOPIC_PREFIX) || destination.startsWith(PRIVATE_QUEUE_PREFIX)
}
