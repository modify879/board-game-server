package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestUseCase
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CancelJoinRequestService(
    private val tables: HoldemTableRepository,
) : CancelJoinRequestUseCase {

    override fun cancel(command: CancelJoinRequestCommand) {
        val table = tables.findById(TableId(command.tableId))
            ?: throw TableNotFoundException("존재하지 않는 테이블입니다: tableId=${command.tableId}")
        table.cancelJoinRequest(command.userId) // 없으면 JoinRequestNotFoundException (도메인이 던진다)
        tables.save(table)
    }
}
