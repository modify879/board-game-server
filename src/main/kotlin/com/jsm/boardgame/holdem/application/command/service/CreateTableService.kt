package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.CreateTableCommand
import com.jsm.boardgame.holdem.application.command.usecase.CreateTableUseCase
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CreateTableService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
) : CreateTableUseCase {

    override fun create(command: CreateTableCommand): TableId {
        val seatedTable = tables.findByUserId(command.ownerUserId)
        if (seatedTable != null) {
            if (handStore.find(seatedTable.id!!) != null) {
                throw HandInProgressException("이미 앉은 테이블에서 핸드가 진행 중입니다: userId=${command.ownerUserId}")
            }
            throw AlreadySeatedException("이미 다른 테이블에 앉아 있는 사용자입니다: userId=${command.ownerUserId}")
        }

        val table = HoldemTable.create(command.name)
        val saved = tables.save(table)
        return saved.id!!
    }
}
