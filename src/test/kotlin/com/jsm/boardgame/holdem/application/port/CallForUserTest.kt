package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 조회 결과를 호출 순서대로 돌려준다 — 마지막 값은 계속 반복한다. */
private class CallForUserFakeTables(private val answers: List<Long?>) : HoldemTableRepository {
    private var i = 0
    override fun findById(id: TableId): HoldemTable? = null
    override fun findByUserId(userId: UserId): HoldemTable? = answers[minOf(i++, answers.size - 1)]?.let {
        HoldemTable.reconstitute(TableId(it), "t", Chips.of(100), Chips.of(200), null, emptyMap())
    }
    override fun findAllSeatedUserIds(): List<UserId> = emptyList()
    override fun findAllTableIds(): List<TableId> = emptyList()
    override fun save(table: HoldemTable): HoldemTable = table
}

private class CallForUserFakeExecutor : TableExecutor {
    val called = mutableListOf<TableId>()
    override fun <T> call(tableId: TableId, task: () -> T): T { called += tableId; return task() }
    override fun post(tableId: TableId, task: () -> Unit) = task()
}

class CallForUserTest {

    private val user = UserId(7L)

    @Test
    fun `앉아 있지 않으면 실행기를 거치지 않고 task 도 돌리지 않으며 onNotSeated 만 돈다`() {
        val executor = CallForUserFakeExecutor()
        var ran = false

        val result = executor.callForUser(CallForUserFakeTables(listOf(null)), user, onNotSeated = { "none" }) { ran = true; "ran" }

        assertEquals("none", result)
        assertEquals(false, ran)
        assertEquals(emptyList(), executor.called)
    }

    @Test
    fun `앉은 테이블이 그대로면 그 테이블의 실행기에서 task 를 돌려 결과를 돌려준다`() {
        val executor = CallForUserFakeExecutor()

        val result = executor.callForUser(CallForUserFakeTables(listOf(1L)), user, onNotSeated = { "none" }) { "ran" }

        assertEquals("ran", result)
        assertEquals(listOf(TableId(1L)), executor.called)
    }

    @Test
    fun `실행기 안에서 다른 테이블로 옮겨 간 걸 보면 task 를 돌리지 않고 새 테이블로 다시 시도한다`() {
        val executor = CallForUserFakeExecutor()
        var runs = 0

        // 1번 조회(밖) 테이블 1, 2번(안) 테이블 2 로 옮겨 감 → 다시 조회(밖) 2, (안) 2
        executor.callForUser(CallForUserFakeTables(listOf(1L, 2L, 2L, 2L)), user, onNotSeated = { Unit }) { runs++ }

        assertEquals(listOf(TableId(1L), TableId(2L)), executor.called)
        assertEquals(1, runs)
    }

    @Test
    fun `계속 옮겨 다니면 두 번 시도한 뒤 포기하고 task 를 돌리지 않는다`() {
        val executor = CallForUserFakeExecutor()
        var runs = 0

        assertFailsWith<IllegalStateException> {
            executor.callForUser(CallForUserFakeTables(listOf(1L, 2L, 3L, 4L)), user, onNotSeated = { Unit }) { runs++ }
        }

        assertEquals(0, runs)
    }
}
