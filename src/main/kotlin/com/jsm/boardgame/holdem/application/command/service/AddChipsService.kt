package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.AddChipsCommand
import com.jsm.boardgame.holdem.application.command.usecase.AddChipsUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
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
 * 칩 추가(하우스 룰 6) — 핸드 사이에만, 스택이 100BB 를 넘지 않는 만큼만 지갑에서 가져온다. 자동 합산은 없다.
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

    override fun addChips(command: AddChipsCommand) {
        val userId = UserId(command.userId)
        val table = tables.findByUserId(userId)
            ?: throw NotSeatedException("어느 테이블에도 앉아 있지 않은 사용자입니다: userId=${command.userId}")
        val tableId = table.id!!
        if (handStore.findInProgress(tableId) != null) {
            throw HandInProgressException("핸드가 진행 중인 테이블에서는 칩을 추가할 수 없습니다: userId=${command.userId}")
        }

        val amount = Chips.of(command.amount)
        table.addChips(table.seatOf(userId)!!.seatNo, amount)
        if (walletBalance.balanceOf(userId) < amount.amount) {
            throw InsufficientBalanceException("지갑 잔액이 추가할 칩보다 적습니다: userId=${command.userId}, amount=${command.amount}")
        }
        walletTransfer.toGame(userId, amount.amount, tableId, memo = "holdem")
        tables.save(table)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, null))
    }
}
