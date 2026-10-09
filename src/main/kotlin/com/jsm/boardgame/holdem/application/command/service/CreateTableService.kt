package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.command.usecase.CreateTableCommand
import com.jsm.boardgame.holdem.application.command.usecase.CreateTableUseCase
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 만든 사람은 앉기 요청까지 한다 — 착석 API 를 그대로 부른 것과 같은 규칙(SeatRequester)이라 사전 조건에
 * 걸리면 같은 에러 코드로 실패하고, 같은 트랜잭션이라 테이블 저장도 함께 롤백된다. 대기열 삽입은
 * 검사를 모두 통과한 뒤 마지막에 일어나므로 롤백된 테이블 앞으로 대기열 항목이 남지 않는다.
 */
@Service
@Transactional
class CreateTableService(
    private val tables: HoldemTableRepository,
    private val seatRequester: SeatRequester,
) : CreateTableUseCase {

    override fun create(command: CreateTableCommand): TableId {
        val saved = tables.save(HoldemTable.create(command.name))
        seatRequester.request(saved, UserId(command.ownerUserId), postBlindImmediately = false)
        return saved.id!!
    }
}
