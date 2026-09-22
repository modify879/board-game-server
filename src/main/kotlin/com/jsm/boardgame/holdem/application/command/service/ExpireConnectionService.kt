package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionUseCase
import com.jsm.boardgame.holdem.application.command.usecase.StandUpCommand
import com.jsm.boardgame.holdem.application.command.usecase.StandUpUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 연결이 3분 넘게 끊긴 사용자를 처리한다. 호출자는 사용자가 아니라
 * [com.jsm.boardgame.holdem.infrastructure.timer.ConnectionTimer] 뿐이라, 미착석이면 조용히
 * 끝낸다(알릴 대상이 없다).
 *
 * 핸드가 진행 중이면 칩을 들고 나갈 수 없다(going south 방지, StandUpService 의 HAND_IN_PROGRESS 와
 * 같은 규칙). 그래서 즉시 기립시키지 않고 테이블 id 만 돌려준다 - 실제 기립은 ConnectionTimer 가
 * 그 테이블의 핸드가 끝난 걸 확인한 뒤([HandBroadcastRequested]) 실행한다.
 *
 * 핸드가 없으면 바로 [StandUpUseCase] 로 기립시킨다. 자동 기립은 사용자가 모르는 사이 스택이
 * 지갑으로 돌아가는 동작이라 반드시 INFO 로 남긴다.
 */
@Service
@Transactional
class ExpireConnectionService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val standUpUseCase: StandUpUseCase,
) : ExpireConnectionUseCase {

    override fun expire(command: ExpireConnectionCommand): TableId? {
        val table = tables.findByUserId(command.userId) ?: return null
        val tableId = table.id!!

        if (handStore.find(tableId) != null) {
            log.info(
                "연결 만료 - 핸드 진행 중이라 퇴장을 예약합니다: userId={}, tableId={}",
                command.userId,
                tableId.value,
            )
            return tableId
        }

        standUpUseCase.standUp(StandUpCommand(command.userId))
        log.info("연결 만료로 즉시 퇴장 처리했습니다: userId={}, tableId={}", command.userId, tableId.value)
        return null
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExpireConnectionService::class.java)
    }
}
