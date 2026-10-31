package com.jsm.boardgame.holdem.application.command.support

import com.jsm.boardgame.holdem.application.event.HandBroadcastRequested
import com.jsm.boardgame.holdem.application.port.FoldWinnerCards
import com.jsm.boardgame.holdem.domain.repository.HandStore
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.application.port.NewHandShuffler
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * 핸드 시작 흐름(블라인드 회전 → 참가 좌석 결정 → 핸드 생성·저장·브로드캐스트)을 한 곳에 둔다 —
 * StartScheduledHandService(자동, 시스템 트리거)가 쓴다. 누가 참가하고 누가 진입료를 내는지(BB 대기·즉시 포스팅·
 * 버튼~첫 블라인드 차단·완화)는 테이블 애그리거트가 정한다([HoldemTable.seatForNextHand]). 착석·기립으로 후보가 바뀔 때의
 * 카운트다운 조정(rescheduleOnEntry/rescheduleOnExit)도 여기 둔다 — 시작 규칙과 카운트다운 규칙이
 * 서로 다른 곳에서 어긋나는 일이 없게 하는 것이 이 클래스의 존재 이유다.
 *
 * 참가자(스택이 있는 점유 좌석)가 2명 미만이면 테이블을 건드리지 않고 false 를 돌려준다 — 그
 * 경우 예외를 던질지 조용히 기다릴지는 호출자가 정한다.
 */
@Component
class HandStarter(
    private val tables: HoldemTableRepository,
    private val handStore: HandStore,
    private val lastHandResults: LastHandResultStore,
    private val foldWinnerCards: FoldWinnerCards,
    private val newHandShuffler: NewHandShuffler,
    private val eventPublisher: ApplicationEventPublisher,
    private val countdown: NextHandCountdown,
) {
    fun start(tableId: TableId, table: HoldemTable): Boolean {
        // 후보 = 점유 좌석 중 스택이 양수인 것. BB 대기 중인 좌석도 후보에 포함한다 — BB 회전은
        // 이들 위로도 지나가야 다음 정상 참가 때 좌석 번호 오름차순 불변식이 깨지지 않는다.
        val candidateSeatNos = table.candidateSeatNos()
        if (candidateSeatNos.size < 2) {
            return false
        }

        countdown.cancel(tableId)

        val seating = table.seatForNextHand(table.advanceBlinds(candidateSeatNos))
        val actual = seating.positions

        val stacks = seating.participantSeatNos.associateWith { seatNo -> table.seatAt(seatNo)!!.stack }
        val hand = Hand.start(
            stacks = stacks,
            buttonSeatNo = actual.buttonSeatNo,
            smallBlindSeatNo = actual.smallBlindSeatNo,
            bigBlindSeatNo = actual.bigBlindSeatNo,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            shuffler = newHandShuffler.shufflerFor(tableId, stacks.keys.sorted(), actual.buttonSeatNo),
            extraPostSeatNos = seating.extraPostSeatNos,
        )

        // 새 핸드가 시작됐으니 직전 결과는 낡았다.
        lastHandResults.remove(tableId)
        foldWinnerCards.remove(tableId)
        handStore.save(tableId, hand)
        tables.save(table)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand, handStarted = true))
        return true
    }

    /** 착석처럼 "들어오는" 좌석 변화 뒤에 부른다.
     *  후보가 2명 이상이면 카운트다운을 지금부터 전체 지연만큼 리셋한다(사용자 결정: 카운트다운
     *  도중 새 참가자가 들어오면 처음부터 다시 센다) — 이미 걸려 있던 예약도 덮어쓴다. 저장과
     *  브로드캐스트는 이 메서드가 계속 책임진다. */
    fun rescheduleOnEntry(tableId: TableId, table: HoldemTable) {
        if (table.candidateSeatNos().size >= 2) {
            countdown.restart(tableId)
        }
        tables.save(table)
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, null))
    }

    /** 기립처럼 "나가는" 좌석 변화 뒤에 부른다(핸드가 있으면 [hand] 로 넘긴다).
     *  후보가 2명 미만으로 떨어지면 취소하고, 아니면 이미 돌고 있는 카운트다운을 그대로 둔다 —
     *  나가는 쪽은 카운트다운을 건드리지 않는다(남은 사람들의 대기 시간을 부당하게 늘리지 않는다).
     *  [hand] 는 진행 중인 핸드(참가하지 않은 좌석이 도중에 일어난 경우)다 — 공개 뷰가 핸드를 잃지 않게 그대로 싣는다. */
    fun rescheduleOnExit(tableId: TableId, table: HoldemTable, hand: Hand? = null) {
        if (table.candidateSeatNos().size < 2) {
            countdown.cancel(tableId)
        }
        tables.save(table)
        // 좌석이 나가도 핸드는 진행되지 않는다 — 진행 중 핸드를 싣더라도 타이머를 다시 걸게 하지 않는다.
        eventPublisher.publishEvent(HandBroadcastRequested(tableId, table, hand, handProgressed = hand == null))
    }
}
