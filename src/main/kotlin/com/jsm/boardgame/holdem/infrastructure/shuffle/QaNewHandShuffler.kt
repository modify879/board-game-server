package com.jsm.boardgame.holdem.infrastructure.shuffle

import com.jsm.boardgame.holdem.application.event.TableClosed
import com.jsm.boardgame.holdem.application.port.NewHandShuffler
import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ConcurrentHashMap

/**
 * 테이블의 "다음 핸드 하나"의 홀카드·보드를 고정하는 QA 전용 셔플러 선택기. `@Profile("qa")` 로만 켜진다
 * (운영 기동 거부는 `QaProfileGuard`). 진행 중 핸드 복원(`HandInProgressStoreAdapter`)은 이 경로를 타지 않는다.
 *
 * 덱 위치 규칙(참가자 n명, 딜 순서 = [dealOrder]):
 * - 딜 순서는 버튼 다음 참가 좌석부터 좌석 번호 오름차순으로 순환하며 버튼이 마지막이다(헤즈업이면 BB, 버튼 순).
 * - 홀카드는 한 장씩 두 바퀴 — r(0,1)번째 장의 덱 위치 = r * n + (딜 순서에서의 index).
 * - 보드는 번 카드 없이 플랍 3장·턴·리버 순이고 k(0..4)번째 장의 덱 위치 = 2n + k.
 * 지정되지 않은 위치는 지정되지 않은 카드를 기본 셔플러로 섞어 채운다.
 *
 * 진행 중 핸드는 액션마다 복원되고 복원은 덱을 저장하지 않아 남은 카드를 다시 섞는다(`.claude/rules/holdem.md`, HandSnapshot) — 그대로면 시작 때
 * 딜되지 않은 보드 카드는 첫 액션 뒤 무작위가 된다. 그래서 고정 덱으로 시작한 핸드의 전체 순서를 [active] 에 기억하고,
 * 복원 셔플러([QaRestoreShuffler])가 남은 카드 집합이 그 덱의 꼬리와 같으면 그 순서를 돌려준다.
 * 딜된 카드는 항상 덱의 앞부분이라 남은 카드는 꼬리와 집합이 같다. 운영 복원 경로·포트는 바뀌지 않는다.
 */
@Component
@Primary
@Profile("qa")
class QaNewHandShuffler(@Qualifier("secureRandomShuffler") private val fallback: Shuffler) : NewHandShuffler {

    private val pending = ConcurrentHashMap<TableId, DeckSpec>()

    /** 고정 덱으로 시작한 핸드의 전체 덱 순서. 그 테이블의 다음 핸드가 시작되면 지운다. */
    private val active = ConcurrentHashMap<TableId, List<Card>>()

    /** 같은 테이블에 다시 예약하면 덮어쓴다. */
    fun reserve(tableId: TableId, spec: DeckSpec) {
        pending[tableId] = spec
    }

    /** 사양은 1회용이다 — 꺼내면서 지운다. 없으면 기본 셔플러 그대로. */
    override fun shufflerFor(tableId: TableId, seatNos: List<Int>, buttonSeatNo: Int): Shuffler {
        active.remove(tableId)
        val spec = pending.remove(tableId) ?: return fallback
        return Shuffler { cards -> arrange(spec, cards, dealOrder(seatNos, buttonSeatNo), fallback).also { active[tableId] = it } }
    }

    /** [remaining] 이 고정 덱의 꼬리와 집합이 같으면 그 꼬리를 덱 순서대로 돌려준다. 아니면 null. */
    fun restoreOrder(remaining: List<Card>): List<Card>? {
        if (remaining.isEmpty()) return null
        val set = remaining.toSet()
        return active.values.firstNotNullOfOrNull { deck -> deck.takeLast(remaining.size).takeIf { it.toSet() == set } }
    }

    @TransactionalEventListener
    fun onTableClosed(event: TableClosed) {
        pending.remove(event.tableId)
        active.remove(event.tableId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(QaNewHandShuffler::class.java)

        /** 버튼 다음 참가 좌석부터 오름차순 순환, 버튼이 마지막. [seatNos] 는 정렬돼 있어야 한다. */
        fun dealOrder(seatNos: List<Int>, buttonSeatNo: Int): List<Int> {
            val start = seatNos.indexOfFirst { it > buttonSeatNo }.takeIf { it >= 0 } ?: 0
            return List(seatNos.size) { seatNos[(start + it) % seatNos.size] }
        }

        fun arrange(spec: DeckSpec, cards: List<Card>, dealOrder: List<Int>, rest: Shuffler): List<Card> {
            val n = dealOrder.size
            val slots = arrayOfNulls<Card>(cards.size)
            spec.holeCards.forEach { (seatNo, hole) ->
                val idx = dealOrder.indexOf(seatNo)
                if (idx < 0) {
                    log.warn("QA 덱: 이번 핸드 참가자가 아닌 좌석의 지정은 무시한다: seatNo={}, 참가={}", seatNo, dealOrder)
                } else {
                    hole.forEachIndexed { r, card -> slots[r * n + idx] = card }
                }
            }
            spec.board.forEachIndexed { k, card -> slots[2 * n + k] = card }

            val fixed = slots.filterNotNull().toSet()
            val pool = rest.shuffle(cards.filter { it !in fixed }).iterator()
            return List(cards.size) { slots[it] ?: pool.next() }
        }
    }
}

/**
 * 검증은 만들 때 한다 — 잘못된 사양이 다음 핸드 시작 도중에 터지면 핸드가 통째로 못 선다.
 * 위반하면 [IllegalArgumentException].
 */
class DeckSpec(val holeCards: Map<Int, List<Card>>, val board: List<Card>) {
    init {
        require(holeCards.keys.all { it in 1..HoldemTable.MAX_SEATS }) { "좌석 번호는 1..${HoldemTable.MAX_SEATS}: ${holeCards.keys}" }
        require(holeCards.values.all { it.size <= 2 }) { "좌석당 홀카드는 최대 2장" }
        require(board.size <= 5) { "보드는 최대 5장" }
        val all = holeCards.values.flatten() + board
        require(all.size == all.toSet().size) { "중복 카드: $all" }
    }
}
