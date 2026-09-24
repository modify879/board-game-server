package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.SeatStatus
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 핸드 종료 정산. StartScheduledHandService·PlayActionService·ExpireTurnService 가 공유한다 —
 * 로직을 복제하지 않는다. 참가 좌석의 스택만 테이블에 반영한다. 칩은 테이블에 남고 기립할 때만
 * 지갑으로 돌아간다.
 *
 * 참가 좌석은 핸드가 소유한다 — 핸드 도중 새로 앉은 좌석은 다음 핸드부터 참가한다.
 *
 * hand.seatNos 중 이미 점유가 풀린 좌석은 ExpireTurnService 가 1분 무응답 폴드로 즉시 기립시킨
 * 좌석뿐이다 — 그 좌석은 이 핸드가 끝날 때까지 스택이 더 바뀌지 않으므로 정산에서 건너뛴다.
 * 폴드가 아닌데 점유가 풀려 있으면 불변식 위반이다(HoldemTable.applyStacks 의 점유 가드는
 * 그대로 둔다 — 여기서는 넘길 대상 자체를 걸러낸다).
 *
 * 스택 반영 직후, 스택이 0이 된 참가 좌석은 자동으로 기립시킨다(0칩 자동 기립) — 9자리가 칩
 * 없는 사람에게 묶이지 않게 한다. 핸드가 이미 끝난 뒤라 going south(핸드 도중 칩을 빼는 것) 제약과
 * 충돌하지 않는다. 돌려줄 칩이 없으므로 지갑 이체는 부르지 않는다(StandUpService 가 스택 0일 때
 * 이체를 건너뛰는 것과 같은 이유) — 하지만 이 클래스가 WalletTransfer 를 아예 안 부르는 것은 아니다.
 * 정산 뒤 처리하는 참가 요청(핸드 도중 관전자가 남긴 착석 요청)의 바이인 이체에는 필요하다.
 *
 * 정산 뒤 다음 핸드 시작 시각(nextHandAt)을 [nextHandDelay] 뒤로 예약한다. DB 에 두는 이유는
 * 재시작해도 이어지고, 대기 중에는 수동 시작을 막아 다른 좌석의 그 시간(기립 결정 시간)을
 * 빼앗지 않기 위해서다. `NextHandTimer` 가 이 값을 보고 `StartScheduledHandUseCase` 를 건다.
 */
@Component
class HandSettler(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    private val walletTransfer: WalletTransfer,
    @Value("\${app.holdem.next-hand-delay}") private val nextHandDelay: Duration,
) {
    fun settle(tableId: TableId, table: HoldemTable, hand: Hand) {
        val stacks = hand.seatNos.associateWith { seatNo -> hand.stackOf(seatNo) }
        val toApply = stacks.filterKeys { seatNo ->
            val occupied = table.seatAt(seatNo) != null
            if (!occupied && hand.statusOf(seatNo) != SeatStatus.FOLDED) {
                error("점유되지 않은 좌석은 폴드 상태여야 한다: seatNo=$seatNo, status=${hand.statusOf(seatNo)}")
            }
            occupied
        }
        table.applyStacks(toApply)

        for ((seatNo, stack) in toApply) {
            if (!stack.isZero()) continue
            val userId = table.seatAt(seatNo)?.userId ?: continue
            table.standUp(userId)
            log.info("auto stand-up after 0-stack settle: tableId={}, seatNo={}, userId={}", tableId.value, seatNo, userId)
        }

        handStore.remove(tableId)
        processJoinRequests(tableId, table)

        table.scheduleNextHand(Instant.now(clock).plus(nextHandDelay))

        tables.save(table)
        // 정산 후에도 hand 를 null 로 넘기지 않는다 — 클라이언트가 쇼다운 결과(showdownRanks/payouts)를
        // 봐야 한다. HandStore 에서는 이미 지웠을 뿐이다.
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
    }

    /** 핸드 도중 들어온 착석 요청을 requestedAt 순서대로 처리한다. 처리 못 하는 요청(다른 테이블에
     *  이미 앉음, 잔액 부족 등)은 정산 자체를 실패시키지 않고 조용히 버린다 — 관전자의 실패한
     *  참가 요청 하나가 핸드 정산 전체를 롤백시키면 안 된다.
     *
     *  지갑 이체를 좌석 배정보다 먼저 한다 — 반대 순서면 이체가 실패했을 때도 이미 좌석에 앉힌
     *  상태(table.sitDown 의 인메모리 변경)가 남아, 뒤이은 tables.save(table) 이 그 좌석을
     *  그대로 커밋해버린다(돈은 안 냈는데 자리는 생기는 셈). 여기서 예외를 밖으로 던지지 않고
     *  이 메서드 안에서 잡기 때문에 SitDownService 처럼 트랜잭션 롤백에 기댈 수 없다. */
    private fun processJoinRequests(tableId: TableId, table: HoldemTable) {
        for (request in table.pendingJoinRequests()) {
            try {
                if (tables.findByUserId(request.userId) != null) {
                    throw AlreadySeatedException("다른 테이블에 이미 앉아 있습니다: userId=${request.userId}")
                }
                walletTransfer.toGame(request.userId, request.buyIn.amount, tableId.value, memo = "holdem")
                table.sitDown(request.seatNo, request.userId, request.buyIn, request.postBlindImmediately)
            } catch (e: BusinessException) {
                log.info(
                    "참가 요청을 처리하지 못해 버립니다: tableId={}, seatNo={}, userId={}, errorCode={}",
                    tableId.value, request.seatNo, request.userId, e.errorCode,
                )
            } finally {
                table.consumeJoinRequest(request.seatNo)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HandSettler::class.java)
    }
}
