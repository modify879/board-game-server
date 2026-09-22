package com.jsm.boardgame.holdem.infrastructure.handstore

import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 진행 중인 핸드를 메모리에 보관한다. DB 를 건드리지 않으므로 persistence 가 아니다 -
 * infrastructure 의 acl 어댑터가 DB 를 안 건드려 persistence 밖에 있는 것과 같은 이유로,
 * 이 저장소도 persistence 패키지 밖에 둔다.
 *
 * ponytail: 단일 인스턴스 전용이라 서버가 재시작되면 진행 중인 핸드가 사라진다.
 * 업그레이드 경로: PostgreSQL 에 holdem_hand_in_progress 상태 스냅샷 테이블을 두는 것,
 * 이 기능의 이후 단계에서 할 일이고 지금은 아니다.
 */
@Component
class InMemoryHandStore : HandStore {
    private val hands = ConcurrentHashMap<TableId, Hand>()

    override fun find(tableId: TableId): Hand? = hands[tableId]

    override fun save(tableId: TableId, hand: Hand) {
        hands[tableId] = hand
    }

    override fun remove(tableId: TableId) {
        hands.remove(tableId)
    }
}
