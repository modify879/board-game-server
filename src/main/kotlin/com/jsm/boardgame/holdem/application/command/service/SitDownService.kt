package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.SitDownCommand
import com.jsm.boardgame.holdem.application.command.usecase.SitDownUseCase
import com.jsm.boardgame.holdem.application.event.JoinRequestsDue
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.NotConnectedException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.UserConnections
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.BuyInOutOfRangeException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 좌석은 더 이상 고르지 않는다 — 모든 착석 요청은 대기열에 들어가고(FIFO), ProcessJoinRequestsService
 * 가 커밋 후 트리거를 받아 가장 낮은 빈 좌석에 순서대로 앉힌다. 바이인 이체는 좌석 배정
 * 시점(AdmitJoinRequestService)에만 일어난다 — 여기서는 지갑을 부르지 않는다.
 *
 * @Transactional 은 DB 를 쓰지 않아도 유지한다 — 커밋 시점이 있어야 [JoinRequestsDue] 를
 * AFTER_COMMIT 으로 미룰 수 있고(JoinRequestsProcessor), 대기열 삽입 자체는 그 커밋 *안에서*
 * 마지막에 일어나야 한다(스펙: enqueue 가 마지막 문장, 그다음 트리거 발행).
 *
 * STOMP 세션이 없는 사용자를 대기열에 넣지 않는다 — 연결이 끊기면 대기열에서 빠지므로
 * (ConnectionTimer), REST 만으로 요청하고 다시는 연결하지 않는 사용자는 영원히 대기열에
 * 남아 순번을 막는다.
 */
@Service
@Transactional
class SitDownService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val joinQueue: JoinQueue,
    private val userConnections: UserConnections,
    private val eventPublisher: ApplicationEventPublisher,
) : SitDownUseCase {

    override fun sitDown(command: SitDownCommand): Int {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId)
            ?: throw TableNotFoundException("존재하지 않는 테이블입니다: tableId=${command.tableId}")

        val seatedTable = tables.findByUserId(command.userId)
        if (seatedTable != null) {
            if (handStore.find(seatedTable.id!!) != null) {
                throw HandInProgressException("이미 앉은 테이블에서 핸드가 진행 중입니다: userId=${command.userId}")
            }
            throw AlreadySeatedException("이미 다른 테이블에 앉아 있는 사용자입니다: userId=${command.userId}")
        }
        if (joinQueue.isQueued(command.userId)) {
            throw AlreadySeatedException("이미 다른 테이블에 참가 요청을 남긴 사용자입니다: userId=${command.userId}")
        }
        if (Chips.of(command.buyIn) < table.bigBlind) {
            throw BuyInOutOfRangeException("바이인은 빅 블라인드(${table.bigBlind}) 이상이어야 합니다: ${command.buyIn}")
        }
        // 개인 큐 유실은 돈 문제다(.claude/rules/holdem.md) — 연결이 없으면 "당신 차례" 대신
        // "좌석 배정" 자체가 증발할 수 있어, 여기서부터 미리 막는다.
        if (!userConnections.isConnected(command.userId)) {
            throw NotConnectedException("STOMP 연결이 없는 사용자는 대기열에 들어갈 수 없습니다: userId=${command.userId}")
        }

        val position = joinQueue.enqueue(tableId, command.userId, Chips.of(command.buyIn), command.postBlindImmediately)
        eventPublisher.publishEvent(JoinRequestsDue(tableId))
        return position
    }
}
