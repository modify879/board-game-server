package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.StartHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.StartHandUseCase
import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.exception.TableNotFoundException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.IllegalHandStateException
import com.jsm.boardgame.holdem.domain.exception.NotSeatedException
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class StartHandService(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val shuffler: Shuffler,
    private val handSettler: HandSettler,
    private val eventPublisher: ApplicationEventPublisher,
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

        // 후보 = 점유 좌석 중 스택이 양수인 것. BB 대기 중인 좌석도 후보에 포함한다 — BB 회전은
        // 이들 위로도 지나가야 다음 정상 참가 때 좌석 번호 오름차순 불변식이 깨지지 않는다.
        val candidateSeatNos = table.occupiedSeats().filter { it.stack.isPositive() }.map { it.seatNo }.toSet()
        if (candidateSeatNos.size < 2) {
            throw IllegalHandStateException(
                HoldemErrorCode.NOT_ENOUGH_PLAYERS,
                "스택이 있는 참가자가 2명 미만입니다: tableId=${command.tableId}, participants=$candidateSeatNos",
            )
        }

        val positions = table.advanceBlinds(candidateSeatNos)
        table.clearAwaitingBigBlind(positions.bigBlindSeatNo)

        // 참가자 = 후보 − (아직 대기 중인데 이번 BB 가 아닌 좌석). 대기 중인 좌석이 SB·버튼 자리에
        // 걸리면 그 자리는 비는 셈이라 dead small blind/dead button 이 그대로 적용된다.
        val awaitingSeatNos = table.occupiedSeats().filter { it.awaitingBigBlind }.map { it.seatNo }.toSet()
        var participatingSeatNos = candidateSeatNos - awaitingSeatNos

        // 대기 규칙은 판을 굶겨 죽이지 않는다: 후보가 2명 이상인데 대기 제외 후 2명 미만이면 판이
        // 못 선다. 판이 못 서면 애초에 건너뛸 블라인드 회전 자체가 없으므로 대기시킬 이유가 없다 —
        // 후보 전원의 대기 플래그를 풀고 전원을 참가시킨다. 테이블의 진짜 첫 핸드(전원 대기 기본값),
        // 비었다가 다시 찬 테이블, 정규 참가자 파산까지 이 규칙 하나로 덮인다.
        if (participatingSeatNos.size < 2) {
            candidateSeatNos.forEach { table.clearAwaitingBigBlind(it) }
            participatingSeatNos = candidateSeatNos
        }

        val smallBlindSeatNo = positions.smallBlindSeatNo?.takeIf { it in participatingSeatNos }

        val owingSeatNos = table.occupiedSeats()
            .filter { it.seatNo in participatingSeatNos && it.owesImmediatePost }
            .map { it.seatNo }
            .toSet()
        val extraPostSeatNos = owingSeatNos - positions.bigBlindSeatNo
        owingSeatNos.forEach { table.consumeImmediatePost(it) }

        val stacks = participatingSeatNos.associateWith { seatNo -> table.seatAt(seatNo)!!.stack }
        val hand = Hand.start(
            stacks = stacks,
            buttonSeatNo = positions.buttonSeatNo,
            smallBlindSeatNo = smallBlindSeatNo,
            bigBlindSeatNo = positions.bigBlindSeatNo,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            shuffler = shuffler,
            extraPostSeatNos = extraPostSeatNos,
        )

        if (hand.isFinished) {
            handSettler.settle(tableId, table, hand)
            return
        }

        handStore.save(tableId, hand)
        tables.save(table)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand))
    }
}
