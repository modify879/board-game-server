package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StandUpCommand
import com.jsm.boardgame.holdem.application.command.usecase.StandUpUseCase
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 핸드가 진행 중이면 기립을 거부한다(going south 방지) — "퇴장 예약" 메커니즘은 만들지 않는다,
 * 연결 끊김 타이머는 4단계(홀덤 WS) 몫이다.
 */
@Service
@Transactional
class StandUpService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val walletTransfer: WalletTransfer,
) : StandUpUseCase {

    override fun standUp(command: StandUpCommand) {
        val table = tables.findByUserId(command.userId)
            ?: throw NotSeatedException("어느 테이블에도 앉아 있지 않은 사용자입니다: userId=${command.userId}")
        val tableId = table.id!!

        if (handStore.find(tableId) != null) {
            throw HandInProgressException("핸드가 진행 중인 테이블에서는 기립할 수 없습니다: userId=${command.userId}")
        }

        val returned = table.standUp(command.userId)
        if (returned.isPositive()) {
            // memo 가 유일하게 "어느 게임인가" 를 나른다 — LedgerEntryType 이 이미 방향을 말하고
            // LedgerReferenceType.GAME_TABLE 엔 게임 이름이 없다. 표시 문구를 넣지 않는다(규칙 8, 문구는 클라이언트가 만든다).
            walletTransfer.fromGame(command.userId, returned.amount, tableId.value, memo = "holdem")
        }
        tables.save(table)
    }
}
