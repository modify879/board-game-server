package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * 핸드 시작 규칙(후보 산정, 블라인드 회전, BB 대기/즉시 포스팅, 완화)을 한 곳에 둔다 —
 * StartHandService(수동, 착석자 트리거)와 StartScheduledHandService(자동, 5초 뒤 시스템 트리거)가
 * 이 규칙을 그대로 공유한다. 두 진입점이 서로 다른 규칙으로 판을 시작하는 일이 없게 하는 것이 이
 * 클래스의 존재 이유다.
 *
 * 참가자(스택이 있는 점유 좌석)가 2명 미만이면 테이블을 건드리지 않고 false 를 돌려준다 — 그
 * 경우 예외를 던질지 조용히 기다릴지는 호출자가 정한다.
 */
@Component
class HandStarter(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val shuffler: Shuffler,
    private val handSettler: HandSettler,
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun start(tableId: TableId, table: HoldemTable): Boolean {
        // 후보 = 점유 좌석 중 스택이 양수인 것. BB 대기 중인 좌석도 후보에 포함한다 — BB 회전은
        // 이들 위로도 지나가야 다음 정상 참가 때 좌석 번호 오름차순 불변식이 깨지지 않는다.
        val candidateSeatNos = table.occupiedSeats().filter { it.stack.isPositive() }.map { it.seatNo }.toSet()
        if (candidateSeatNos.size < 2) {
            return false
        }

        table.clearNextHand()

        val positions = table.advanceBlinds(candidateSeatNos)
        table.clearAwaitingBigBlind(positions.bigBlindSeatNo)

        val awaitingSeatNos = table.occupiedSeats().filter { it.awaitingBigBlind }.map { it.seatNo }.toSet()

        // 착석 시 "즉시 포스팅"을 고른 좌석. Robert's Rules of Poker, Button and Blind Use: 새 참가자는
        // 포스팅을 자청해도 SB·버튼 자리에서는 딜인되지 않는다 — 버튼이 지나갈 때까지 대기한다.
        val owingSeatNos = table.occupiedSeats()
            .filter { it.seatNo in candidateSeatNos && it.owesImmediatePost }
            .map { it.seatNo }
            .toSet()
        val blockedNewPlayers = owingSeatNos.filter { it == positions.buttonSeatNo || it == positions.smallBlindSeatNo }.toSet()

        // 참가자 = 후보 − (아직 대기 중인데 이번 BB 가 아닌 좌석) − (SB·버튼 자리에 걸린 즉시 포스팅
        // 신규 좌석). 대기 중인 좌석이 SB·버튼 자리에 걸리면 그 자리는 비는 셈이라 dead small
        // blind/dead button 이 그대로 적용된다.
        var participatingSeatNos = candidateSeatNos - awaitingSeatNos - blockedNewPlayers

        // 대기·차단 규칙은 판을 굶겨 죽이지 않는다: 후보가 2명 이상인데 제외 후 2명 미만이면 판이
        // 못 선다. 판이 못 서면 애초에 건너뛸 블라인드 회전 자체가 없으므로 대기·차단시킬 이유가
        // 없다 — 후보 전원의 대기 플래그를 풀고 전원을 참가시키며, 이번 핸드만큼은 진입료도
        // 면제한다(테이블이 사실상 다시 시작하는 셈이라 BB 대기 면제와 같은 이유다). 테이블의 진짜
        // 첫 핸드(전원 대기 기본값), 비었다가 다시 찬 테이블, 정규 참가자 파산까지 이 규칙 하나로 덮인다.
        val relief = participatingSeatNos.size < 2
        if (relief) {
            candidateSeatNos.forEach { table.clearAwaitingBigBlind(it) }
            participatingSeatNos = candidateSeatNos
        }

        val actual = positions.forParticipants(participatingSeatNos)

        // 블라인드 포스팅 자체가 이번 핸드의 진입료다 — 빚진 좌석이 실제 SB/BB 로 좁혀지면 추가
        // 포스팅을 따로 받지 않는다. 대기 좌석은 owing 이 될 수 없으므로(즉시 참가를 고른 좌석만
        // owing), 빚진 좌석이 실제 SB 가 되는 경로는 헤즈업 좁히기(다른 좌석은 전부 대기)뿐이다 —
        // 그때는 SB 포스팅을 진입료 대신으로 받아들이는 의도된 단순화다.
        val extraPostSeatNos = if (relief) {
            emptySet()
        } else {
            owingSeatNos.intersect(participatingSeatNos) - setOfNotNull(actual.smallBlindSeatNo, actual.bigBlindSeatNo)
        }

        owingSeatNos.filter { it in participatingSeatNos }.forEach { table.consumeImmediatePost(it) }

        val stacks = participatingSeatNos.associateWith { seatNo -> table.seatAt(seatNo)!!.stack }
        val hand = Hand.start(
            stacks = stacks,
            buttonSeatNo = actual.buttonSeatNo,
            smallBlindSeatNo = actual.smallBlindSeatNo,
            bigBlindSeatNo = actual.bigBlindSeatNo,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            shuffler = shuffler,
            extraPostSeatNos = extraPostSeatNos,
        )

        if (hand.isFinished) {
            handSettler.settle(tableId, table, hand)
            return true
        }

        handStore.save(tableId, hand)
        tables.save(table)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
        return true
    }
}
