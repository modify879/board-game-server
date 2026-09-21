package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class HoldemTableQueryRepositoryAdapterIntegrationTest {

    private val buyIn: Chips = Chips.of(10_000)

    @Autowired
    private lateinit var tableQuery: HoldemTableQueryRepository

    @Autowired
    private lateinit var tables: HoldemTableRepository

    @Autowired
    private lateinit var users: UserRepository

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
    fun `findSeatOf - 사용자가 앉아 있으면 tableId, seatNo 를 반환한다`() {
        val userId = uniqueUserId()
        val table = HoldemTable.create("q1")
        table.sitDown(4, userId, buyIn)
        val saved = tables.save(table)

        val found = tableQuery.findSeatOf(userId)

        assertEquals(saved.id!!.value, found?.tableId)
        assertEquals(4, found?.seatNo)
    }

    @Test
    fun `findSeatOf - 앉아 있지 않으면 null 이다`() {
        val userId = uniqueUserId()

        assertNull(tableQuery.findSeatOf(userId))
    }

    @Test
    fun `findAllTables - 페이지네이션과 occupiedSeats, maxSeats 가 올바르다`() {
        val userA = uniqueUserId()
        val userB = uniqueUserId()

        val table1 = HoldemTable.create("q2")
        table1.sitDown(1, userA, buyIn)
        table1.sitDown(2, userB, buyIn)
        val saved1 = tables.save(table1)

        val table2 = HoldemTable.create("q3")
        val saved2 = tables.save(table2)

        val summaries = mutableListOf<TableSummaryView>()
        var page = tableQuery.findAllTables(PageRequest.of(0, 1))
        summaries += page.content
        while (page.hasNext()) {
            page = tableQuery.findAllTables(page.nextPageable())
            summaries += page.content
        }

        val summary1 = summaries.single { it.tableId == saved1.id!!.value }
        val summary2 = summaries.single { it.tableId == saved2.id!!.value }

        assertEquals(2, summary1.occupiedSeats)
        assertEquals(HoldemTable.MAX_SEATS, summary1.maxSeats)
        assertEquals(0, summary2.occupiedSeats)
        assertEquals(HoldemTable.MAX_SEATS, summary2.maxSeats)
        assertTrue(summaries.map { it.tableId }.toSet().containsAll(listOf(saved1.id!!.value, saved2.id!!.value)))
    }
}
