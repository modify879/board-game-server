package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 테이블별 브로드캐스트 순번. 메모리 전용이다 — 재시작하면 0부터 다시 세지만, 재시작은 모든
 * 소켓을 끊어버리므로 클라이언트는 재구독(재접속 포함) 시 채널별 last seq 를 리셋한다
 * (TablePublicView 의 KDoc 에 적은 클라이언트 계약).
 */
@Component
class TableViewSequence {

    private val counters = ConcurrentHashMap<Long, AtomicLong>()

    /** 증가 후 값을 돌려준다. */
    fun next(tableId: TableId): Long =
        counters.computeIfAbsent(tableId.value) { AtomicLong(0) }.incrementAndGet()

    /** 증가시키지 않고 현재 값을 읽는다. 아직 한 번도 next() 가 불린 적 없으면 0. */
    fun current(tableId: TableId): Long =
        counters[tableId.value]?.get() ?: 0L
}
