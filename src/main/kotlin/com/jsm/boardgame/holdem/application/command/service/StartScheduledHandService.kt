package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * [com.jsm.boardgame.holdem.infrastructure.timer.NextHandTimer] 가 예약 시각에 부르는 시스템 시작.
 * 호출자는 사용자가 아니라 타이머뿐이라 실패를 알릴 대상이 없다 — 조건이 안 맞으면 조용히 끝낸다.
 */
@Service
@Transactional
class StartScheduledHandService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val handStarter: HandStarter,
    private val clock: Clock,
) : StartScheduledHandUseCase {

    override fun start(command: StartScheduledHandCommand) {
        val tableId = TableId(command.tableId)
        val table = tables.findById(tableId) ?: return
        if (handStore.find(tableId) != null) return
        val nextHandAt = table.nextHandAt ?: return
        // 재무장된 새 타이머와 경합해 먼저 도착한 stale 실행을 조용히 거른다.
        if (Instant.now(clock).isBefore(nextHandAt)) return

        if (!handStarter.start(tableId, table)) {
            table.clearNextHand()
            tables.save(table)
            log.info("대기 인원이 2명 미만이라 자동 시작을 건너뜁니다: tableId={}", command.tableId)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(StartScheduledHandService::class.java)
    }
}
