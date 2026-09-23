package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.SitDownCommand
import com.jsm.boardgame.holdem.application.command.usecase.SitDownUseCase
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 기본 전파(REQUIRED)를 그대로 쓴다 — 지갑 차감(walletTransfer.toGame)과 착석(tables.save)이
 * 한 트랜잭션이어야 잔액 부족 시 착석까지 롤백된다.
 */
@Service
@Transactional
class SitDownService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val walletTransfer: WalletTransfer,
) : SitDownUseCase {

    override fun sitDown(command: SitDownCommand) {
        val table = tables.findById(TableId(command.tableId))
            ?: throw TableNotFoundException("존재하지 않는 테이블입니다: tableId=${command.tableId}")

        val seatedTable = tables.findByUserId(command.userId)
        if (seatedTable != null) {
            if (handStore.find(seatedTable.id!!) != null) {
                throw HandInProgressException("이미 앉은 테이블에서 핸드가 진행 중입니다: userId=${command.userId}")
            }
            throw AlreadySeatedException("이미 다른 테이블에 앉아 있는 사용자입니다: userId=${command.userId}")
        }

        // 도메인 검증(좌석 범위·점유·바이인 범위)을 지갑 차감보다 먼저 해서, 좌석이 이미 찼는데
        // 지갑만 빠지는 순서가 생기지 않게 한다.
        table.sitDown(command.seatNo, command.userId, Chips.of(command.buyIn), command.postBlindImmediately)

        // memo 가 유일하게 "어느 게임인가" 를 나른다 — LedgerEntryType 이 이미 방향을 말하고
        // LedgerReferenceType.GAME_TABLE 엔 게임 이름이 없다. 표시 문구를 넣지 않는다(규칙 8, 문구는 클라이언트가 만든다).
        walletTransfer.toGame(command.userId, command.buyIn, command.tableId, memo = "holdem")
        tables.save(table)
    }
}
