package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StartHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartHandUseCase
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalHandStateException
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class StartHandService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val handStarter: HandStarter,
) : StartHandUseCase {

    override fun start(command: StartHandCommand) {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId)
            ?: throw TableNotFoundException("테이블을 찾을 수 없습니다: tableId=${command.tableId}")
        table.seatOf(command.userId)
            ?: throw NotSeatedException("이 테이블에 앉아 있지 않은 사용자입니다: userId=${command.userId}")

        if (handStore.find(tableId) != null) {
            throw HandInProgressException("이미 진행 중인 핸드가 있습니다: tableId=${command.tableId}")
        }
        if (table.nextHandAt != null) {
            throw HandInProgressException("다음 핸드가 자동으로 시작될 예정입니다: tableId=${command.tableId}")
        }

        if (!handStarter.start(tableId, table)) {
            throw IllegalHandStateException(
                HoldemErrorCode.NOT_ENOUGH_PLAYERS,
                "스택이 있는 참가자가 2명 미만입니다: tableId=${command.tableId}",
            )
        }
    }
}
