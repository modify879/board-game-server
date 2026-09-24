package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    private fun newTableId(): TableId = tables.save(HoldemTable.create("t-${System.nanoTime()}")).id!!

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
    fun `다른 트랜잭션이 먼저 저장한 핸드를 덮어쓰면 CONCURRENT_TABLE_UPDATE 로 거부되고 먼저 저장한 상태가 남는다`() {
        val tableId = newTableId()
        handStore.save(tableId, newHand())

        val outerTemplate = TransactionTemplate(transactionManager)
        val innerTemplate = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
        var innerToActSeatNo: Int? = null

        val e = assertFailsWith<ConcurrentTableUpdateException> {
            outerTemplate.execute {
                // 이 findById 가 이 트랜잭션의 영속성 컨텍스트에 version=0 인 채로 캐시된다.
                val outerHand = handStore.find(tableId)!!

                // REQUIRES_NEW 라 완전히 새 트랜잭션·EntityManager 로 읽고, 액션 후 저장하고,
                // execute 가 반환하기 전에 커밋까지 끝난다 — "이미 다른 트랜잭션이 커밋한" 상황을 만든다.
                innerTemplate.execute {
                    val innerHand = handStore.find(tableId)!!
                    innerHand.act(innerHand.toActSeatNo!!, BettingAction.Call)
                    handStore.save(tableId, innerHand)
                    innerToActSeatNo = innerHand.toActSeatNo
                }

                // outer 가 재개된 뒤에도 findById 는 1차 캐시의 stale(version=0) 인스턴스를 그대로 돌려주므로
                // 이 save 의 버전 있는 UPDATE 는 DB 의 더 앞선 버전과 충돌해 낙관적 락 예외를 낸다.
                outerHand.act(outerHand.toActSeatNo!!, BettingAction.Call)
                handStore.save(tableId, outerHand)
            }
        }
        assertEquals(HoldemErrorCode.CONCURRENT_TABLE_UPDATE, e.errorCode)

        val found = handStore.find(tableId)
        assertEquals(innerToActSeatNo, found?.toActSeatNo)
    }

    @Test
    fun `다른 트랜잭션이 먼저 갱신한 핸드를 stale 버전으로 지우려 하면 CONCURRENT_TABLE_UPDATE 로 거부되고 행이 남는다`() {
        val tableId = newTableId()
        handStore.save(tableId, newHand())

        val outerTemplate = TransactionTemplate(transactionManager)
        val innerTemplate = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

        val e = assertFailsWith<ConcurrentTableUpdateException> {
            outerTemplate.execute {
                val outerHand = handStore.find(tableId)!!

                innerTemplate.execute {
                    val innerHand = handStore.find(tableId)!!
                    innerHand.act(innerHand.toActSeatNo!!, BettingAction.Call)
                    handStore.save(tableId, innerHand)
                }

                // outerHand 는 여전히 stale(version=0) 이라 remove 의 버전 있는 DELETE 가 충돌한다.
                handStore.remove(tableId)
            }
        }
        assertEquals(HoldemErrorCode.CONCURRENT_TABLE_UPDATE, e.errorCode)

        val rowCount = jdbcTemplate.queryForObject(
            "select count(*) from holdem_hand_in_progress where table_id = ?",
            Long::class.java,
            tableId.value,
        )
        assertEquals(1L, rowCount)
    }

    @Test
    fun `같은 테이블에 핸드를 두 번 새로 저장하면 HAND_IN_PROGRESS 로 거부된다`() {
        // 각 스레드가 자기만의 REQUIRES_NEW 트랜잭션(자기만의 커넥션·EntityManager) 안에서
        // handStore.save() 를 한 번씩 부른다. 둘 다 상대가 아직 커밋하기 전에 findById 를 보므로
        // 둘 다 "없음" 을 보고 새 엔티티로 saveAndFlush 를 시도한다. Postgres 가 그 두 INSERT 를
        // 행 잠금으로 직렬화해, 먼저 도착한 쪽이 삽입하고(REQUIRES_NEW 라 execute 가 반환하며 즉시 커밋),
        // 나중 쪽은 같은 PK 에서 유일성 위반으로 즉시 실패한다 — 이 어댑터는 그걸 HandInProgressException 으로 옮긴다.
        val tableId = newTableId() // holdem_tables 행만 있고 핸드 행은 아직 없다.
        val startLatch = CountDownLatch(2)
        val results = arrayOfNulls<Throwable>(2)
        val raceTemplate = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

        fun race(index: Int) {
            startLatch.countDown()
            startLatch.await()
            try {
                raceTemplate.execute {
                    handStore.save(tableId, newHand())
                }
            } catch (t: Throwable) {
                results[index] = t
            }
        }

        val t1 = Thread { race(0) }
        val t2 = Thread { race(1) }
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        val failures = results.filterNotNull()
        assertEquals(1, failures.size)
        val failure = failures.single()
        assertEquals(true, failure is HandInProgressException)
        assertEquals(HoldemErrorCode.HAND_IN_PROGRESS, (failure as HandInProgressException).errorCode)

        assertNotNull(handStore.find(tableId))
    }
}
