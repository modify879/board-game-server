package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.AddChipsCommand
import com.jsm.boardgame.holdem.application.command.usecase.AddChipsResult
import com.jsm.boardgame.holdem.application.command.usecase.AddChipsUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.exception.InsufficientBalanceException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 칩 추가(하우스 룰 6) — 스택이 테이블 정책의 최대 바이인(기본 100BB)을 넘지 않는 만큼만 지갑에서 가져온다. 자동 합산은 없다.
 * 핸드가 진행 중이고 좌석이 그 참가자이면 바로 더하지 않고 좌석에 예약만 건다([AddChipsResult.RESERVED], 덮어쓴다) —
 * 적용은 핸드가 끝난 뒤 [ApplyChipsReservationsService] 가 별도 트랜잭션으로 한다. 예약 때는 한도·잔액을 보지 않는다(단위·양수만).
 * 테이블 스레드 위에서 돌므로 락 순서는 테이블 → 지갑이다. 새 스택이 모두에게 보이도록 공개 뷰를 브로드캐스트한다.
 */
@Service
@Transactional
class AddChipsService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val walletBalance: WalletBalance,
    private val walletTransfer: WalletTransfer,
    private val eventPublisher: ApplicationEventPublisher,
) : AddChipsUseCase {

    override fun addChips(command: AddChipsCommand): AddChipsResult {
        val userId = UserId(command.userId)
        val table = tables.findByUserId(userId)
            ?: throw NotSeatedException("어느 테이블에도 앉아 있지 않은 사용자입니다: userId=${command.userId}")
        val tableId = table.id!!
        val seatNo = table.seatOf(userId)!!.seatNo
        val amount = Chips.of(command.amount)

        // 핸드 참가자만 예약한다. 핸드 도중 앉아 이번 핸드에 참가하지 않은 좌석은 칩이 핸드에 걸려 있지 않아 즉시 더한다.
        val hand = handStore.findInProgress(tableId)
        if (hand != null && seatNo in hand.seatNos) {
            // 한도·잔액은 핸드가 끝난 뒤(스택이 확정된 뒤) 적용 때 본다.
            table.reserveChips(userId, amount)
            tables.save(table)
            eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand, handProgressed = false))
            return AddChipsResult.RESERVED
        }

        table.addChips(seatNo, amount)
        if (walletBalance.balanceOf(userId) < amount.amount) {
            throw InsufficientBalanceException("지갑 잔액이 추가할 칩보다 적습니다: userId=${command.userId}, amount=${command.amount}")
        }
        walletTransfer.toGame(userId, amount.amount, tableId, memo = "holdem")
        tables.save(table)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, null))
        return AddChipsResult.ADDED
    }
}
