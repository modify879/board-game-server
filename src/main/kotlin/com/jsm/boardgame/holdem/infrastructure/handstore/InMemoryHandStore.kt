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
 * 이 기능의 이후 단계에서 할 일이고 지금은 아니다. 또한 보관된 Hand 는 가변 객체인데
 * 테이블별 잠금이 없다. ConcurrentHashMap 은 맵 자체의 연산을 원자화하지만, 맵이 들고 있는
 * Hand 객체 내부는 지키지 않는다. 같은 테이블에서 두 요청이 동시에 Hand.act() 를 부르면
 * 두 번째는 대개 NOT_YOUR_TURN 으로 막히지만, act() 내부가 인터리빙되면 상태가 깨질 수 있다.
 * 테이블 행의 @Version 은 테이블만 보호하고 메모리의 Hand 는 지키지 않는다. 5단계에서
 * Hand 가 holdem_hand_in_progress 행으로 옮겨가면 트랜잭션 격리가 직렬화를 맡아 이 구멍이
 * 사라진다. 그때 지울 코드를 지금 넣지 않는다.
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
