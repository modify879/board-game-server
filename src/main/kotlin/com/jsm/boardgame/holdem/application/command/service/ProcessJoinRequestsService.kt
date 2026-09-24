package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.AdmitJoinRequestUseCase
import com.jsm.boardgame.holdem.application.command.usecase.DropJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.DropJoinRequestUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ProcessJoinRequestsCommand
import com.jsm.boardgame.holdem.application.command.usecase.ProcessJoinRequestsUseCase
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 참가 요청을 requestedAt 순서대로, 각각 자기 트랜잭션으로 처리한다(AdmitJoinRequestService).
 * 이 클래스 자체는 @Transactional 이 아니다 — 요청 하나의 실패가 다른 요청 처리에 영향을 주지
 * 않게 하는 것이 이 격리의 목적이라, 이 메서드 전체를 하나의 트랜잭션으로 묶으면 그 목적이
 * 무의미해진다.
 */
@Service
class ProcessJoinRequestsService(
    private val tables: HoldemTableRepository,
    private val admitJoinRequestUseCase: AdmitJoinRequestUseCase,
    private val dropJoinRequestUseCase: DropJoinRequestUseCase,
) : ProcessJoinRequestsUseCase {

    override fun process(command: ProcessJoinRequestsCommand) {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId) ?: return

        for (request in table.pendingJoinRequests()) {
            try {
                admitJoinRequestUseCase.admit(AdmitJoinRequestCommand(tableId.value, request.userId))
            } catch (e: ConcurrentTableUpdateException) {
                log.info("참가 요청 처리가 경합해 건너뜁니다: tableId={}, userId={}", tableId.value, request.userId)
            } catch (e: BusinessException) {
                log.info(
                    "참가 요청을 처리하지 못해 버립니다: tableId={}, userId={}, errorCode={}",
                    tableId.value, request.userId, e.errorCode,
                )
                dropJoinRequestUseCase.drop(DropJoinRequestCommand(tableId.value, request.userId))
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProcessJoinRequestsService::class.java)
    }
}
