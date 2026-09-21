package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.common.persistence.violatedConstraint
import com.jsm.boardgame.holdem.domain.exception.AlreadySeatedException
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
import com.jsm.boardgame.holdem.domain.exception.SeatTakenException
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.Seat
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HoldemSeatJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HoldemSeatJpaRepository
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HoldemTableJpaRepository
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.toDomain
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.toJpaEntity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Repository

private const val CONSTRAINT_SEAT_USER = "uk_holdem_seats_user"
private const val CONSTRAINT_SEAT_TABLE_SEAT = "uk_holdem_seats_table_seat"

@Repository
class HoldemTableRepositoryAdapter(
    private val tableJpa: HoldemTableJpaRepository,
    private val seatJpa: HoldemSeatJpaRepository,
) : HoldemTableRepository {

    override fun findById(id: TableId): HoldemTable? {
        val tableEntity = tableJpa.findById(id.value).orElse(null) ?: return null
        return tableEntity.toDomain(seatJpa.findAllByTableId(tableEntity.id))
    }

    override fun findByUserId(userId: Long): HoldemTable? {
        val seatEntity = seatJpa.findByUserId(userId) ?: return null
        return findById(TableId(seatEntity.tableId))
    }

    override fun save(table: HoldemTable): HoldemTable {
        val savedTable = try {
            tableJpa.saveAndFlush(table.toJpaEntity())
        } catch (e: OptimisticLockingFailureException) {
            // 테이블 행만 버전을 갖는다. 착석·기립 둘 다 테이블 행을 저장하므로 경합은 이 버전에서 드러난다.
            // 기립이 충돌했을 때 SEAT_TAKEN 을 내보내면 계약이 사실과 어긋나므로 wallet 의
            // CONCURRENT_WALLET_UPDATE 와 같은 자리에 전용 코드를 둔다.
            throw ConcurrentTableUpdateException("낙관적 락 충돌: ${e.message}")
        }

        val existingSeats = seatJpa.findAllByTableId(savedTable.id).associateBy { it.seatNo }
        val occupied = table.occupiedSeats()
        val occupiedSeatNos = occupied.map { it.seatNo }.toSet()

        val toDelete = existingSeats.values.filter { it.seatNo !in occupiedSeatNos }
        if (toDelete.isNotEmpty()) {
            seatJpa.deleteAll(toDelete)
            seatJpa.flush()
        }

        try {
            for (seat in occupied) {
                val existingId = existingSeats[seat.seatNo]?.id ?: 0
                seatJpa.saveAndFlush(seat.toSeatJpaEntity(tableId = savedTable.id, existingId = existingId))
            }
        } catch (e: DataIntegrityViolationException) {
            throw translateSeat(e)
        } catch (e: OptimisticLockingFailureException) {
            throw ConcurrentTableUpdateException("낙관적 락 충돌: ${e.message}")
        }

        return savedTable.toDomain(seatJpa.findAllByTableId(savedTable.id))
    }

    // 부모 테이블의 DB id 와, 기존 행이 있다면 그 행의 id(있으면 재사용해 unique 제약이
    // 스푸리어스하게 튀지 않도록 한다)라는 외부 컨텍스트가 필요해서 엔티티 파일이 아니라 여기 둔다.
    private fun Seat.toSeatJpaEntity(tableId: Long, existingId: Long): HoldemSeatJpaEntity =
        HoldemSeatJpaEntity(
            id = existingId,
            tableId = tableId,
            seatNo = seatNo,
            userId = userId,
            stack = stack.amount,
            presence = presence.name,
        )

    private fun translateSeat(e: DataIntegrityViolationException): RuntimeException {
        val constraintName = e.violatedConstraint(CONSTRAINT_SEAT_USER, CONSTRAINT_SEAT_TABLE_SEAT) ?: return e
        return when (constraintName) {
            CONSTRAINT_SEAT_USER -> AlreadySeatedException("unique 제약 위반: $constraintName")
            CONSTRAINT_SEAT_TABLE_SEAT -> SeatTakenException("unique 제약 위반: $constraintName")
            else -> e
        }
    }
}
