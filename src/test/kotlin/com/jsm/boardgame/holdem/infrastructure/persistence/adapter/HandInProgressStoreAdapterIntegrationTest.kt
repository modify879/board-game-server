package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandInProgressJpaRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class HandInProgressStoreAdapterIntegrationTest {

    @Autowired
    private lateinit var handStore: HandStore

    @Autowired
    private lateinit var handJpa: HandInProgressJpaRepository

    @Autowired
    private lateinit var tables: HoldemTableRepository

    @Autowired
    private lateinit var shuffler: Shuffler

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    // fk_hand_in_progress_table 때문에 실제 holdem_tables 행이 있어야 한다.
    private fun newTableId(): TableId = tables.save(HoldemTable.create("t-${System.nanoTime()}", TestTablePolicy)).id!!

    private fun newHand(): Hand = Hand.start(
        stacks = mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)),
        buttonSeatNo = 1,
        smallBlindSeatNo = 2,
        bigBlindSeatNo = 3,
        smallBlind = Chips.of(100),
        bigBlind = Chips.of(200),
        shuffler = shuffler,
    )

    @Test
    fun `저장 후 복원한 핸드는 같은 차례 좌석·같은 스냅샷·같은 보드·같은 홀카드를 갖는다`() {
        val tableId = newTableId()
        val hand = newHand()

        handStore.save(tableId, hand)
        val found = handStore.find(tableId)

        assertNotNull(found)
        assertEquals(hand.snapshot(), found.snapshot())
        assertEquals(hand.toActSeatNo, found.toActSeatNo)
        assertEquals(hand.board, found.board)
        for (seatNo in hand.seatNos) {
            assertEquals(hand.holeCardsOf(seatNo), found.holeCardsOf(seatNo))
        }
    }

    @Test
    fun `같은 테이블에 두 번 저장하면 행 하나가 덮어써진다`() {
        val tableId = newTableId()
        val hand = newHand()
        handStore.save(tableId, hand)

        val actingSeatNo = hand.toActSeatNo!!
        hand.act(actingSeatNo, BettingAction.Call)
        handStore.save(tableId, hand)

        val rowCount = jdbcTemplate.queryForObject(
            "select count(*) from holdem_hand_in_progress where table_id = ?",
            Long::class.java,
            tableId.value,
        )
        assertEquals(1L, rowCount)

        val found = handStore.find(tableId)
        assertEquals(hand.toActSeatNo, found?.toActSeatNo)
    }

    @Test
    fun `remove 후 find 는 null 이다`() {
        val tableId = newTableId()
        handStore.save(tableId, newHand())

        handStore.remove(tableId)

        assertNull(handStore.find(tableId))
    }

    @Test
    fun `한 번도 저장한 적 없는 테이블의 remove 는 조용히 아무 일도 하지 않는다`() {
        val tableId = newTableId()

        handStore.remove(tableId)

        assertNull(handStore.find(tableId))
    }

    @Test
    fun `저장된 state JSON 에는 아직 딜되지 않은 카드가 없다`() {
        val tableId = newTableId()
        val hand = newHand()
        handStore.save(tableId, hand)

        val rawState = jdbcTemplate.queryForObject(
            "select state::text from holdem_hand_in_progress where table_id = ?",
            String::class.java,
            tableId.value,
        )
        assertNotNull(rawState)
        assertFalse(rawState.contains("deck", ignoreCase = true))

        val dealtCards = hand.seatNos.flatMap { hand.holeCardsOf(it) }.map { it.toString() }.toSet() + hand.board.map { it.toString() }
        val cardTokenPattern = Regex("\"([2-9TJQKA][shdc])\"")
        val cardsInJson = cardTokenPattern.findAll(rawState).map { it.groupValues[1] }.toSet()
        assertEquals(dealtCards, cardsInJson)
    }

    @Test
    fun `복원한 핸드로 액션을 이어 진행할 수 있다`() {
        val tableId = newTableId()
        val hand = newHand()
        handStore.save(tableId, hand)

        val found = handStore.find(tableId)!!
        val actingSeatNo = found.toActSeatNo!!
        found.act(actingSeatNo, BettingAction.Call)

        assertNotNull(found.toActSeatNo)
        assertFalse(found.toActSeatNo == actingSeatNo)
    }

    @Test
    fun `같은 테이블에 핸드를 두 번 새로 저장하면 HAND_IN_PROGRESS 로 거부된다`() {
        // 결정적 안무 — 타이밍에 기대지 않는다.
        // 1) 스레드 1: REQUIRES_NEW 트랜잭션에서 save() 로 INSERT 를 flush 하되 커밋하지 않고 "inserted" 를 올린 뒤 "release" 를 기다린다.
        // 2) 스레드 2: "inserted" 이후 자기 REQUIRES_NEW 에서 save() — findById 는 MVCC 라 행을 못 봐 사전 체크를 지나쳐
        //    INSERT 로 가고, 스레드 1 의 미커밋 PK 때문에 Lock 대기에 들어간다(DB 제약 번역 경로를 반드시 탄다).
        // 3) 메인: pg_stat_activity 에서 Lock 대기 백엔드가 보일 때까지 폴링(최대 2초)한 뒤 release 한다.
        //    앱의 lock_timeout=3s 보다 한참 앞서 풀어야 하므로 대기는 짧게 묶는다.
        // 스레드 1 이 커밋하면 스레드 2 의 INSERT 가 유일성 위반으로 실패하고, 어댑터가 HandInProgressException 으로 옮긴다.
        val tableId = newTableId() // holdem_tables 행만 있고 핸드 행은 아직 없다.
        val inserted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val results = arrayOfNulls<Throwable>(2)
        val raceTemplate = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

        val t1 = Thread {
            try {
                raceTemplate.execute {
                    handStore.save(tableId, newHand())
                    inserted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            } catch (t: Throwable) {
                results[0] = t
            }
        }
        val t2 = Thread {
            try {
                inserted.await(5, TimeUnit.SECONDS)
                raceTemplate.execute { handStore.save(tableId, newHand()) }
            } catch (t: Throwable) {
                results[1] = t
            }
        }
        t1.start()
        t2.start()

        try {
            assertTrue(inserted.await(5, TimeUnit.SECONDS), "스레드 1 이 INSERT 하지 못했다")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'",
                    Long::class.java,
                ) == 0L
            ) {
                assertTrue(System.nanoTime() < deadline, "스레드 2 가 Lock 대기에 들어가지 않았다")
                Thread.sleep(10)
            }
        } finally {
            release.countDown()
        }
        t1.join()
        t2.join()

        assertNull(results[0], "스레드 1 은 성공해야 한다")
        val failures = results.filterNotNull()
        assertEquals(1, failures.size)
        val failure = failures.single()
        assertEquals(true, failure is HandInProgressException)
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, (failure as HandInProgressException).errorCode)

        assertNotNull(handStore.find(tableId))
    }
}
