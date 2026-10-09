package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.event.JoinRequestsDue
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.NotConnectedException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.UserConnections
import com.jsm.boardgame.holdem.application.port.WalletBalance
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 착석 요청의 사전 조건 검사와 대기열 삽입. 착석(SitDownService)과 테이블 생성(CreateTableService)이
 * 같은 규칙으로 앉도록 공유한다 — 호출자의 트랜잭션에 참여하며 스스로 트랜잭션을 열지 않는다.
 * 대기열 삽입은 마지막 문장이라, 앞선 검사가 실패하면 대기열에는 아무 흔적도 남지 않는다.
 */
@Component
class SeatRequester(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val joinQueue: JoinQueue,
    private val userConnections: UserConnections,
    private val walletBalance: WalletBalance,
    private val eventPublisher: ApplicationEventPublisher,
) {

    /** [table] 은 저장돼 id 가 있어야 한다. 1-based 대기열 순번을 돌려준다. */
    fun request(table: HoldemTable, userId: UserId, postBlindImmediately: Boolean): Int {
        val tableId = table.id!!
        val seatedTable = tables.findByUserId(userId)
        if (seatedTable != null) {
            if (handStore.find(seatedTable.id!!) != null) {
                throw HandInProgressException("이미 앉은 테이블에서 핸드가 진행 중입니다: userId=$userId")
            }
            throw AlreadySeatedException("이미 다른 테이블에 앉아 있는 사용자입니다: userId=$userId")
        }
        if (joinQueue.isQueued(userId)) {
            throw AlreadySeatedException("이미 다른 테이블에 참가 요청을 남긴 사용자입니다: userId=$userId")
        }
        val balance = walletBalance.balanceOf(userId)
        if (Chips.of(balance) < table.bigBlind) {
            throw BuyInOutOfRangeException("지갑 잔액이 빅 블라인드(${table.bigBlind}) 미만입니다: $balance")
        }
        // 개인 큐 유실은 돈 문제다(.claude/rules/holdem.md) — 연결이 없으면 "당신 차례" 대신
        // "좌석 배정" 자체가 증발할 수 있어, 여기서부터 미리 막는다.
        if (!userConnections.isConnected(userId)) {
            throw NotConnectedException("STOMP 연결이 없는 사용자는 대기열에 들어갈 수 없습니다: userId=$userId")
        }

        val position = joinQueue.enqueue(tableId, userId, postBlindImmediately)
        // 인메모리 대기열은 트랜잭션을 모른다 — 호출자가 롤백하면 항목만 남아 사용자가 어디에도 못 앉게 된다.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK && joinQueue.tableOf(userId) == tableId) {
                        joinQueue.removeByUserId(userId)
                    }
                }
            })
        }
        eventPublisher.publishEvent(JoinRequestsDue(tableId))
        return position
    }
}
