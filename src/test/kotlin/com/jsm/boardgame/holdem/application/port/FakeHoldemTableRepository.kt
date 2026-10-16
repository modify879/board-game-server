package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository

/** 서비스 테스트가 공유하는 인메모리 대역. 저장할 때 복사하고 읽을 때는 저장본을 그대로 돌려준다. */
class FakeHoldemTableRepository : HoldemTableRepository {
    val stored = mutableMapOf<Long, HoldemTable>()
    private var sequence = 0L

    override fun findById(id: TableId): HoldemTable? = stored[id.value]

    override fun findByUserId(userId: UserId): HoldemTable? = stored.values.find { it.seatOf(userId) != null }

    override fun findAllSeatedUserIds(): List<UserId> = stored.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllTableIds(): List<TableId> = stored.values.mapNotNull { it.id }

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: run { sequence += 1; TableId(sequence) }
        val saved = HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
        )
        stored[id.value] = saved
        return saved
    }
}

/** [FakeHoldemTableRepository] 와 같되 읽을 때도 복사본을 돌려줘, 읽은 테이블을 고쳐도 저장본이 안 바뀐다. */
class FakeCopyingHoldemTableRepository : HoldemTableRepository {
    private val store = mutableMapOf<Long, HoldemTable>()
    private var nextId = 1L

    override fun findById(id: TableId): HoldemTable? = store[id.value]?.let { copyOf(it) }

    override fun findByUserId(userId: UserId): HoldemTable? =
        store.values.firstOrNull { it.seatOf(userId) != null }?.let { copyOf(it) }

    override fun findAllSeatedUserIds(): List<UserId> = store.values.flatMap { it.occupiedSeats() }.map { it.userId }

    override fun findAllTableIds(): List<TableId> = store.values.mapNotNull { it.id }

    override fun save(table: HoldemTable): HoldemTable {
        val id = table.id ?: TableId(nextId++)
        val saved = copyOf(table, id)
        store[id.value] = saved
        return saved
    }

    private fun copyOf(table: HoldemTable, id: TableId = table.id!!): HoldemTable =
        HoldemTable.reconstitute(
            id = id,
            name = table.name,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            buttonSeatNo = table.buttonSeatNo,
            seats = table.occupiedSeats().associateBy { it.seatNo },
        )
}
