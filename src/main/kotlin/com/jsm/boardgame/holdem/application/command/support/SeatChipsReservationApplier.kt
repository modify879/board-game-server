package com.jsm.boardgame.holdem.application.command.support

import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.domain.repository.HandStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.application.port.RecentDepartures
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 좌석 하나의 칩 추가 예약을 자기만의 트랜잭션으로 적용한다. 유스케이스가 아니라 [com.jsm.boardgame.holdem.application.command.service.ApplyChipsReservationsService]
 * 의 협력자다 — 별도 빈인 이유는 클래스 단위 REQUIRES_NEW 가 프록시를 거쳐야 걸리기 때문이다. REQUIRES_NEW 인 이유는 [com.jsm.boardgame.holdem.application.command.service.AdmitJoinRequestService] 와 같다 —
 * 지갑 이체 실패가 앞서 적용한 좌석이나 정산 트랜잭션에 번지지 않아야 하고, 호출자는 커밋 후 실행기 스레드에서 돈다.
 *
 * 스택은 핸드가 끝난 뒤 값이다 — 한도(최대 바이인 − 스택)와 잔액으로 줄이고 [CHIP_UNIT] 으로 내림한 만큼만 지갑에서 가져온다.
 * 더한 뒤에도 스택이 0 이면(예: 파산했는데 잔액이 없다) 0칩 자동 기립과 똑같이 기립시킨다. 칩을 받은 좌석이 남아 후보가 2명 이상이면
 * 새 입장처럼 카운트다운을 다시 센다.
 */
@Component
@Transactional(propagation = Propagation.REQUIRES_NEW)
class SeatChipsReservationApplier(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val walletBalance: WalletBalance,
    private val walletTransfer: WalletTransfer,
    private val recentDepartures: RecentDepartures,
    private val countdown: NextHandCountdown,
    private val eventPublisher: ApplicationEventPublisher,
) {

    fun apply(tableId: TableId, userId: UserId) {
        val table = load(tableId) ?: return
        val seat = table.seatOf(userId) ?: return
        if (seat.chipsReservation == null) return

        // 지갑 잔액은 holdem 밖의 금액이라 경계에서 칩 단위로 내림해 들인다(규칙 7).
        val amount = table.applyChipsReservation(userId, Chips.floorOf(walletBalance.balanceOf(userId)))
        if (amount.isPositive()) {
            walletTransfer.toGame(userId, amount.amount, tableId, memo = "holdem")
        }
        val stoodUp = standUpIfEmpty(tableId, table, userId)
        tables.save(table)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, null))
        // 칩을 받고 남은 좌석은 새 입장과 같다. 0칩 기립은 나가는 쪽이라 카운트다운을 건드리지 않는다.
        if (!stoodUp && table.candidateSeatNos().size >= 2) {
            countdown.restart(tableId)
        }
        log.info("칩 추가 예약을 적용했습니다: tableId={}, userId={}, added={}", tableId.value, userId.value, amount.amount)
    }

    /** 적용이 실패했을 때 예약만 지우고, 스택이 0 이면 일으켜 세운다 — 0칩 좌석이 영영 남지 않게 한다. */
    fun drop(tableId: TableId, userId: UserId) {
        val table = load(tableId) ?: return
        if (table.seatOf(userId)?.chipsReservation == null) return

        table.cancelChipsReservation(userId)
        standUpIfEmpty(tableId, table, userId)
        tables.save(table)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, null))
    }

    private fun load(tableId: TableId): HoldemTable? {
        val table = tables.findById(tableId) ?: return null
        if (handStore.findInProgress(tableId) != null) return null
        return table
    }

    /** 스택이 0 이면 지갑 없이 기립시키고 재착석 의무를 0 으로 덮는다(HandSettler 의 0칩 자동 기립과 같다). */
    private fun standUpIfEmpty(tableId: TableId, table: HoldemTable, userId: UserId): Boolean {
        if (!table.seatOf(userId)!!.stack.isZero()) return false
        table.standUp(userId)
        recentDepartures.record(userId, tableId, Chips.ZERO)
        log.info("auto stand-up after empty chips reservation: tableId={}, userId={}", tableId.value, userId.value)
        return true
    }

    private companion object {
        private val log = LoggerFactory.getLogger(SeatChipsReservationApplier::class.java)
    }
}
