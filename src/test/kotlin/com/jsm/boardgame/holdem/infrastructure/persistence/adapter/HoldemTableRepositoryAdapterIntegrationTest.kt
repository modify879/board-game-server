package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.SeatTakenException
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.Seat
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HoldemSeatJpaRepository
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class HoldemTableRepositoryAdapterIntegrationTest {

    private val buyIn: Chips = Chips.of(10_000)

    @Autowired
    private lateinit var tables: HoldemTableRepository

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var seatJpa: HoldemSeatJpaRepository

    // fk_holdem_seats_user 가 걸려 있어 합성 userId 로는 착석 행을 저장할 수 없다 — 실제 사용자 행을 만든다.
    private fun uniqueUserId(): Long {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(9).lowercase()
        val user = User.register(
            username = Username.of("u$suffix"),
            passwordHash = PasswordHash("hashed-password-value"),
            nickname = Nickname.of("n" + suffix.take(5)),
        )
        return users.save(user).id!!.value
    }

    @Test
    fun `테이블 저장 후 복원 - 좌석 포함, buttonSeatNo 는 null`() {
        val userId = uniqueUserId()
        val table = HoldemTable.create("t1")
        table.sitDown(3, userId, buyIn)

        val saved = tables.save(table)
        val found = tables.findById(saved.id!!)

        assertNotNull(found)
        assertNull(found.buttonSeatNo)
        val seat = found.occupiedSeats().single()
        assertEquals(3, seat.seatNo)
        assertEquals(userId, seat.userId)
        assertEquals(buyIn, seat.stack)
    }

    @Test
    fun `테이블 저장 후 복원 - buttonSeatNo 값이 있는 경우`() {
        val userId = uniqueUserId()
        val table = HoldemTable.create("t2")
        table.sitDown(2, userId, buyIn)
        table.moveButtonToNextOccupiedSeat()

        val saved = tables.save(table)
        val found = tables.findById(saved.id!!)

        assertEquals(2, found?.buttonSeatNo)
    }

    @Test
    fun `findByUserId 가 사용자가 앉은 테이블을 찾는다`() {
        val userId = uniqueUserId()
        val table = HoldemTable.create("t3")
        table.sitDown(1, userId, buyIn)
        val saved = tables.save(table)

        val found = tables.findByUserId(userId)

        assertEquals(saved.id, found?.id)
    }

    @Test
    fun `기립 후 해당 좌석 DB 행이 삭제된다`() {
        val userId = uniqueUserId()
        val table = HoldemTable.create("t4")
        table.sitDown(5, userId, buyIn)
        val saved = tables.save(table)

        saved.standUp(userId)
        tables.save(saved)

        assertTrue(seatJpa.findAllByTableId(saved.id!!.value).isEmpty())
    }

    // applyStacks 로 기존 좌석 값을 바꾼 뒤 두 번째 save() 가 실제로 UPDATE 를 타는지 확인한다.
    // save() 만 쓰면 UPDATE 가 커밋 시점에야 flush 되는 함정이 이 저장소의 역사적 버그 종류다.
    @Test
    fun `스택 변경이 UPDATE 로 반영된다`() {
        val userId = uniqueUserId()
        val table = HoldemTable.create("t5")
        table.sitDown(1, userId, buyIn)
        val saved = tables.save(table)

        saved.applyStacks(mapOf(1 to Chips.of(15_000)))
        tables.save(saved)

        val found = tables.findById(saved.id!!)
        assertEquals(Chips.of(15_000), found?.occupiedSeats()?.single()?.stack)
    }

    @Test
    fun `uk_holdem_seats_user 위반은 AlreadySeatedException 으로 번역된다`() {
        val userX = uniqueUserId()
        val table1 = HoldemTable.create("t6")
        table1.sitDown(1, userX, buyIn)
        tables.save(table1)

        val table2 = tables.save(HoldemTable.create("t7"))
        table2.sitDown(1, userX, buyIn)

        val e = assertFailsWith<AlreadySeatedException> { tables.save(table2) }
        assertEquals(HoldemErrorCode.ALREADY_SEATED, e.errorCode)
    }

    // 좌석만 바뀌고 테이블 행 자체의 컬럼(name/blind/buttonSeatNo)이 그대로면 Hibernate 가
    // dirty 로 보지 않아 UPDATE 를 생략하고 version 도 그대로다 — 그러면 "stale" 스냅샷이 실은
    // stale 이 아니게 된다. moveButtonToNextOccupiedSeat() 로 buttonSeatNo 를 실제로 바꿔
    // 테이블 행 UPDATE 가 진짜 일어나고 version 이 올라가도록 만든다.
    @Test
    fun `낙관적 락 경합(stale 버전)은 ConcurrentTableUpdateException 으로 번역된다`() {
        val userA = uniqueUserId()
        val userB = uniqueUserId()

        val created = tables.save(HoldemTable.create("t8"))
        val staleVersion = created.version

        created.sitDown(1, userA, buyIn)
        created.moveButtonToNextOccupiedSeat()
        tables.save(created)

        val staleSnapshot = HoldemTable.reconstitute(
            id = created.id!!,
            name = created.name,
            smallBlind = created.smallBlind,
            bigBlind = created.bigBlind,
            buttonSeatNo = null,
            seats = mapOf(1 to Seat.reconstitute(1, userB, buyIn, SeatPresence.SEATED)),
            version = staleVersion,
        )

        val e = assertFailsWith<ConcurrentTableUpdateException> { tables.save(staleSnapshot) }
        assertEquals(HoldemErrorCode.CONCURRENT_TABLE_UPDATE, e.errorCode)
    }
}
