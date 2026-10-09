package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 좌석 하나의 지갑 잔액을 스택에 합친다 — 좌석마다 자기 트랜잭션이다. 지갑 행 락 대기·실패가 호출자
 * (정산·핸드 시작·다른 좌석의 합산)의 트랜잭션을 rollback-only 로 만들면 안 되기 때문이다. 같은
 * 트랜잭션에 참여한 프록시를 예외가 한 번이라도 넘으면 바깥에서 잡아도 롤백이므로 REQUIRES_NEW 다.
 * 그래서 [TableSeatsTopUp] 처럼 다른 빈이 이 프록시를 불러야 한다(같은 클래스 안 호출은 프록시를 안 탄다).
 *
 * 테이블을 여기서 새로 읽는다 — 호출자가 들고 있는 테이블 객체는 이 트랜잭션 밖의 것이다.
 * 핸드가 진행 중이면 합치지 않는다(table stakes). 락 순서는 테이블 → 지갑이다
 * (테이블 스레드 위라 테이블은 이미 직렬화돼 있다). 합쳤으면 true.
 */
@Component
class SeatTopUper(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val walletTransfer: WalletTransfer,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun topUp(tableId: TableId, userId: UserId): Boolean {
        val table = tables.findById(tableId) ?: return false
        val seat = table.seatOf(userId) ?: return false
        if (handStore.find(tableId) != null) return false

        val added = walletTransfer.allToGame(userId, tableId, memo = "holdem")
        if (added <= 0) return false
        table.topUp(seat.seatNo, Chips.of(added))
        tables.save(table)
        return true
    }
}
