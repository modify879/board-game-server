package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.service.Shuffler
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 하우스 룰 6번: 무작위 시뮬레이션 + 불변식, 최소 오라클(독립 구현한 팟 분배), 메타모픽(무늬 치환).
 * 시드 = BASE + i. 실패 메시지에 시드와 액션 목록이 들어간다.
 * `-Dholdem.sim.hands=N` 으로 횟수를, `-Dholdem.sim.seed=S` 로 한 시드만 재생한다.
 */
class HandSimulationTest {

    private sealed interface Step {
        data class Act(val seatNo: Int, val action: BettingAction) : Step
        data object Runout : Step
    }

    private data class Setup(
        val stacks: Map<Int, Chips>,
        val button: Int,
        val sb: Int,
        val bb: Int,
        val extraPost: Set<Int>,
    )

    private class Outcome(val hand: Hand, val setup: Setup)

    private fun chips(amount: Long) = Chips.of(amount)

    /** 시뮬레이션이 실제로 각 경로를 밟았는지 세는 카운터 — 하나라도 0 이면 검사가 공회전하는 것이다. */
    private val counts = sortedMapOf<String, Long>()

    private fun count(key: String) { counts[key] = (counts[key] ?: 0L) + 1 }

    private fun genSetup(rng: Random): Setup {
        val n = rng.nextInt(2, 10)
        val seats = (1..9).shuffled(rng).take(n).sorted()
        val stacks = seats.associateWith {
            val units = if (rng.nextInt(5) == 0) rng.nextLong(1, 4) else rng.nextLong(1, 401) // 100..300 은 블라인드 이하 숏스택
            chips(units * 100)
        }
        val button = seats.random(rng)
        fun after(seat: Int) = seats.firstOrNull { it > seat } ?: seats.first()
        val sb: Int
        val bb: Int
        if (n == 2) {
            sb = button
            bb = after(button)
        } else {
            sb = after(button)
            bb = after(sb)
        }
        val extra = if (rng.nextInt(4) == 0) {
            seats.filter { it != button && it != sb && it != bb && rng.nextBoolean() }.toSet()
        } else {
            emptySet()
        }
        return Setup(stacks, button, sb, bb, extra)
    }

    /** 고정 무늬 치환(순환) — 카드 순서는 시드가 정하고 무늬만 바꾼다. */
    private fun shufflerFor(seed: Long, suitShift: Int) = Shuffler { cards ->
        cards.shuffled(Random(seed + 1)).map { Card(it.rank, Suit.entries[(it.suit.ordinal + suitShift) % 4]) }
    }

    private fun pickAction(rng: Random, a: AvailableActions): BettingAction {
        val options = mutableListOf<Pair<Int, BettingAction>>()
        if (a.canCheck) options += 50 to BettingAction.Check
        if (a.callAmount != null) options += 40 to BettingAction.Call
        options += (if (a.canCheck) 2 else 8) to BettingAction.Fold
        if (a.minRaiseTo != null && a.maxRaiseTo != null) {
            val lo = a.minRaiseTo.amount
            val hi = a.maxRaiseTo.amount
            val to = when (rng.nextInt(6)) {
                0 -> hi
                1, 2 -> lo
                else -> lo + rng.nextLong(0, (hi - lo) / 100 + 1) * 100
            }
            options += 20 to BettingAction.RaiseTo(chips(to))
        }
        var r = rng.nextInt(options.sumOf { it.first })
        for ((w, act) in options) {
            if (r < w) return act
            r -= w
        }
        error("unreachable")
    }

    private fun assertIllegal(hand: Hand, code: HoldemErrorCode?, what: String, block: () -> Unit) {
        count("illegal-attempt")
        val before = hand.snapshot()
        val e = runCatching(block).exceptionOrNull()
        assertTrue(e is BusinessException, "$what: BusinessException 이 아니다: $e")
        if (code != null) assertEquals(code, e.errorCode, what)
        assertEquals(before, hand.snapshot(), "$what: 스냅샷이 바뀌었다")
    }

    private fun tryIllegal(rng: Random, hand: Hand) {
        if (hand.isFinished) {
            assertIllegal(hand, HoldemErrorCode.HAND_ALREADY_FINISHED, "종료 후 act") { hand.act(hand.seatNos.first(), BettingAction.Check) }
            assertIllegal(hand, HoldemErrorCode.HAND_NOT_RUNNING_OUT, "종료 후 runout") { hand.advanceRunout() }
            return
        }
        if (hand.isRunningOut) {
            assertIllegal(hand, HoldemErrorCode.BETTING_ROUND_CLOSED, "런아웃 중 act") { hand.act(hand.seatNos.first(), BettingAction.Check) }
            return
        }
        val toAct = hand.toActSeatNo!!
        val avail = hand.availableActionsFor(toAct)!!
        val currentBet = hand.snapshot().currentRound!!.currentBet
        when (rng.nextInt(4)) {
            0 -> assertIllegal(hand, HoldemErrorCode.RAISE_TOO_SMALL, "현재 베팅 이하 레이즈") { hand.act(toAct, BettingAction.RaiseTo(currentBet)) }
            1 -> if (!avail.canCheck) {
                assertIllegal(hand, HoldemErrorCode.CANNOT_CHECK, "체크 불가 체크") { hand.act(toAct, BettingAction.Check) }
            }
            2 -> assertIllegal(hand, HoldemErrorCode.NOT_YOUR_TURN, "차례 아닌 좌석") {
                hand.act(hand.seatNos.first { it != toAct }, BettingAction.Fold)
            }
            else -> assertIllegal(hand, null, "스택 초과 레이즈") {
                hand.act(toAct, BettingAction.RaiseTo(hand.stackOf(toAct) + chips(currentBet.amount + 100)))
            }
        }
    }

    private fun checkInvariants(hand: Hand, setup: Setup) {
        val total = setup.stacks.values.fold(Chips.ZERO) { a, c -> a + c }
        val stackSum = hand.seatNos.fold(Chips.ZERO) { a, s -> a + hand.stackOf(s) }
        if (hand.isFinished) {
            assertEquals(total, stackSum, "종료 후 스택 합")
        } else {
            assertEquals(total, hand.potTotal() + stackSum, "팟 + 스택 합")
        }
        hand.toActSeatNo?.let { seat ->
            assertEquals(SeatStatus.ACTIVE, hand.statusOf(seat), "차례 좌석은 ACTIVE 여야 한다: $seat")
            hand.availableActionsFor(seat)!!.callAmount?.let { assertTrue(it <= hand.stackOf(seat), "call > stack") }
        }
    }

    /** [script] 가 null 이면 무작위로 진행하며 [steps] 에 기록한다. 아니면 그 스크립트를 그대로 재생한다. */
    private fun drive(seed: Long, setup: Setup, suitShift: Int, steps: MutableList<Step>, script: List<Step>?): Outcome {
        val hand = Hand.start(setup.stacks, setup.button, setup.sb, setup.bb, chips(100), chips(200), shufflerFor(seed, suitShift), setup.extraPost)
        val rng = Random(seed * 31 + 7)
        val outcome = Outcome(hand, setup)
        if (script != null) {
            script.forEach { s ->
                when (s) {
                    is Step.Act -> hand.act(s.seatNo, s.action)
                    Step.Runout -> hand.advanceRunout()
                }
            }
            return outcome
        }
        checkInvariants(hand, setup)
        var guard = 0
        while (!hand.isFinished) {
            check(++guard < 5_000) { "핸드가 끝나지 않는다" }
            if (rng.nextInt(8) == 0) tryIllegal(rng, hand)
            if (hand.isRunningOut) {
                hand.advanceRunout()
                steps += Step.Runout
            } else {
                val seat = hand.toActSeatNo!!
                val action = pickAction(rng, hand.availableActionsFor(seat)!!)
                steps += Step.Act(seat, action)
                hand.act(seat, action)
            }
            checkInvariants(hand, setup)
        }
        tryIllegal(rng, hand)
        return outcome
    }

    // ---- 오라클: 사이드팟을 일부러 순진하게 다시 계산한다 ----

    private fun oraclePayouts(hand: Hand, ranks: Map<Int, HandRank>): Map<Int, Long> {
        val seats = hand.seatNos
        val contrib = seats.associateWith { hand.totalContributedBy(it).amount }.toMutableMap()
        val live = seats.filter { hand.statusOf(it) != SeatStatus.FOLDED }.toSet()
        val pay = seats.associateWith { 0L }.toMutableMap()

        // 언콜드 벳: 단독 최고 투입자가 둘째 투입액 위의 몫을 돌려받는다.
        val sorted = contrib.values.sortedDescending()
        if (sorted[0] > sorted[1]) {
            val top = contrib.entries.first { it.value == sorted[0] }.key
            count("uncalled-return")
            pay[top] = pay.getValue(top) + sorted[0] - sorted[1]
            contrib[top] = sorted[1]
        }

        // 층: 투입 레벨별로 (자격자 집합, 금액). 자격자가 없는 층은 바로 아래 층에 얹고,
        // 자격자 집합이 같은 인접 층은 합친다(프로덕션과 같은 나머지 칩 규칙을 따른다).
        val pots = mutableListOf<Pair<Set<Int>, Long>>()
        var prev = 0L
        for (level in contrib.values.filter { it > 0 }.distinct().sorted()) {
            val amount = contrib.values.sumOf { minOf(it, level) - minOf(it, prev) }
            val eligible = live.filter { contrib.getValue(it) >= level }.toSet()
            prev = level
            when {
                eligible.isEmpty() -> pots[pots.lastIndex] = pots.last().first to pots.last().second + amount
                pots.isNotEmpty() && pots.last().first == eligible -> pots[pots.lastIndex] = eligible to pots.last().second + amount
                else -> pots += eligible to amount
            }
        }

        val order = seats.filter { it > hand.buttonSeatNo } + seats.filter { it <= hand.buttonSeatNo }
        for ((eligible, amount) in pots) {
            val best = eligible.mapNotNull { ranks[it] }.maxWithOrNull(naturalOrder())
            val winners = order.filter { it in eligible && (eligible.size == 1 || ranks.getValue(it).compareTo(best!!) == 0) }
            val units = amount / CHIP_UNIT
            if (units % winners.size != 0L) count("odd-chip-split")
            winners.forEachIndexed { i, w ->
                val share = units / winners.size + if (i < units % winners.size) 1 else 0
                pay[w] = pay.getValue(w) + share * CHIP_UNIT
            }
        }
        return pay
    }

    private fun verifyFinished(hand: Hand) {
        val result = hand.result!!
        val seats = hand.seatNos
        val contributed = seats.sumOf { hand.totalContributedBy(it).amount }
        assertEquals(contributed, result.payouts.values.sumOf { it.amount }, "지급 합 == 총 투입액")
        val notFolded = seats.filter { hand.statusOf(it) != SeatStatus.FOLDED }.toSet()
        seats.filter { it !in notFolded }.forEach { assertEquals(0L, result.payouts[it]?.amount ?: 0L, "폴드 좌석 $it 은 아무것도 받지 않는다") }
        if (notFolded.size == 1) {
            assertTrue(result.showdownRanks.isEmpty(), "폴드 승리면 showdownRanks 가 비어야 한다")
        } else {
            assertEquals(notFolded, result.showdownRanks.keys, "showdownRanks 키 == 폴드 안 한 좌석")
            assertEquals(5, hand.board.size, "쇼다운이면 보드 5장")
            result.showdownRanks.forEach { (seat, rank) -> assertEquals(HandEvaluator.evaluate(hand.holeCardsOf(seat) + hand.board), rank) }
        }
        count(if (notFolded.size == 1) "fold-win" else "showdown")
        if (result.pots.size >= 2) count("side-pot")
        val expected = oraclePayouts(hand, result.showdownRanks)
        val actual = seats.associateWith { result.payouts[it]?.amount ?: 0L }
        assertEquals(expected, actual, "오라클 지급 불일치")
    }

    private fun runSeed(seed: Long) {
        val steps = mutableListOf<Step>()
        val setup = genSetup(Random(seed))
        try {
            val base = drive(seed, setup, 0, steps, null)
            verifyFinished(base.hand)
            if (steps.any { it is Step.Runout }) count("runout")
            val shift = (seed % 3).toInt() + 1
            val permuted = drive(seed, setup, shift, mutableListOf(), steps.toList())
            val a = base.hand.result!!
            val b = permuted.hand.result!!
            assertTrue(permuted.hand.isFinished, "메타모픽: 재생이 끝나지 않았다")
            assertEquals(a.payouts, b.payouts, "메타모픽: 지급이 다르다")
            assertEquals(a.showdownRanks, b.showdownRanks, "메타모픽: 쇼다운 랭크가 다르다")
            assertEquals(
                base.hand.seatNos.filter { base.hand.statusOf(it) == SeatStatus.FOLDED },
                permuted.hand.seatNos.filter { permuted.hand.statusOf(it) == SeatStatus.FOLDED },
                "메타모픽: 폴드 좌석이 다르다",
            )
        } catch (t: Throwable) {
            throw AssertionError("seed=$seed (-Dholdem.sim.seed=$seed) setup=$setup steps=$steps cause=$t", t)
        }
    }

    private val seedProperty: Long? = System.getProperty("holdem.sim.seed")?.toLong()

    @Test
    fun `무작위 핸드 시뮬레이션이 불변식과 오라클과 무늬 치환 메타모픽을 모두 지킨다`() {
        assumeTrue(seedProperty == null, "단일 시드 재생 중")
        val hands = System.getProperty("holdem.sim.hands")?.toInt() ?: 3_000
        repeat(hands) { runSeed(BASE_SEED + it) }
        println("holdem simulation: hands=$hands $counts")
        val required = listOf("fold-win", "showdown", "runout", "side-pot", "odd-chip-split", "uncalled-return", "illegal-attempt")
        // 기본 횟수에서만 요구한다 — 몇 판만 돌리는 재현 실행에서는 못 밟는 경로가 있을 수 있다.
        if (hands >= 3_000) assertTrue(required.all { (counts[it] ?: 0L) > 0 }, "밟지 못한 경로가 있다: hands=$hands $counts")
    }

    @Test
    fun `시드 하나를 재생한다`() {
        assumeTrue(seedProperty != null, "-Dholdem.sim.seed 가 없다")
        runSeed(seedProperty!!)
    }

    private companion object {
        const val BASE_SEED = 20_260_000L
    }
}
