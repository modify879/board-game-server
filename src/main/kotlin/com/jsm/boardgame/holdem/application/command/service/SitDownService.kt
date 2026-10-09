package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.command.usecase.SitDownCommand
import com.jsm.boardgame.holdem.application.command.usecase.SitDownUseCase
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 좌석은 더 이상 고르지 않는다 — 모든 착석 요청은 대기열에 들어가고(FIFO), ProcessJoinRequestsService
 * 가 커밋 후 트리거를 받아 가장 낮은 빈 좌석에 순서대로 앉힌다. 바이인(지갑 잔액 전부) 이체는 좌석 배정
 * 시점(AdmitJoinRequestService)에만 일어난다 — 사전 조건 검사와 삽입은 [SeatRequester] 가 한다(테이블 생성과 공유).
 *
 * @Transactional 은 DB 를 쓰지 않아도 유지한다 — 커밋 시점이 있어야 `JoinRequestsDue` 를
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
    private val seatRequester: SeatRequester,
) : SitDownUseCase {

    override fun sitDown(command: SitDownCommand): Int {
        val table = tables.findById(TableId(command.tableId))
            ?: throw TableNotFoundException("존재하지 않는 테이블입니다: tableId=${command.tableId}")
        return seatRequester.request(table, UserId(command.userId), command.postBlindImmediately)
    }
}
