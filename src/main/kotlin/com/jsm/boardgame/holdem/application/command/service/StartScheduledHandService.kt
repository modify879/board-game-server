package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartScheduledHandUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

/**
 * [com.jsm.boardgame.holdem.infrastructure.timer.NextHandTimer] 가 카운트다운이 끝나면 부르는 시스템
 * 시작. 호출자는 사용자가 아니라 타이머뿐이라 실패를 알릴 대상이 없다 — 조건이 안 맞으면 조용히
 * 끝낸다. 벽시계와는 아무것도 비교하지 않는다 — 발화 자체가 이미 상대 시간(단조 시계)으로
 * 결정됐다(NextHandCountdown 참고).
 *
 * 착석 중 지갑에 들어온 돈의 합산은 시작 트랜잭션 **앞**에서 한다(좌석별 REQUIRES_NEW, 실패는 건너뜀) —
 * 지갑 행 락 대기·실패가 시작을 롤백시켜 테이블이 멈추면 안 되기 때문이다. 시작 트랜잭션은 그 뒤에
 * 테이블을 새로 읽으므로 합쳐진 스택을 본다. 그래서 클래스 전체가 @Transactional 이 아니다.
 */
@Service
class StartScheduledHandService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val handStarter: HandStarter,
    private val tableSeatsTopUp: TableSeatsTopUp,
    private val transactions: TransactionOperations,
) : StartScheduledHandUseCase {

    override fun start(command: StartScheduledHandCommand) {
        val tableId = TableId(command.tableId)
        if (tables.findById(tableId) == null || handStore.find(tableId) != null) return

        tableSeatsTopUp.topUpAll(tableId)

        transactions.executeWithoutResult { startInTransaction(tableId) }
    }

    private fun startInTransaction(tableId: TableId) {
        val table = tables.findById(tableId) ?: return
        if (handStore.find(tableId) != null) return

        // 이 시각에 아직 남아 있는 참가 요청은 이번 핸드에 딜인되지 않는다 — HandStarter.start 는 점유 좌석만 딜인하고 참가 요청은 건드리지 않는다. 핸드가 끝난 뒤 처리된다(작은 경합 창, 허용 가능한 수준).
        if (!handStarter.start(tableId, table)) {
            log.info("대기 인원이 2명 미만이라 자동 시작을 건너뜁니다: tableId={}", tableId.value)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(StartScheduledHandService::class.java)
    }
}
