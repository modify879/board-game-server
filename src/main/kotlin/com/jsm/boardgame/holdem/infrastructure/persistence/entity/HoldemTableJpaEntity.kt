package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@Table(name = "holdem_tables")
class HoldemTableJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "name", columnDefinition = "text", nullable = false)
    var name: String,
    @Column(name = "small_blind", nullable = false)
    var smallBlind: Long,
    @Column(name = "big_blind", nullable = false)
    var bigBlind: Long,
    @Column(name = "button_seat_no")
    var buttonSeatNo: Int?,
    @Column(name = "small_blind_seat_no")
    var smallBlindSeatNo: Int?,
    @Column(name = "big_blind_seat_no")
    var bigBlindSeatNo: Int?,
    @Column(name = "next_hand_at")
    var nextHandAt: Instant?,
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

interface HoldemTableJpaRepository : JpaRepository<HoldemTableJpaEntity, Long>, KotlinJdslJpqlExecutor {
    fun findAllByNextHandAtIsNotNull(): List<HoldemTableJpaEntity>
}

fun HoldemTableJpaEntity.toDomain(seats: List<HoldemSeatJpaEntity>, joinRequests: List<HoldemJoinRequestJpaEntity> = emptyList()): HoldemTable =
    HoldemTable.reconstitute(
        id = TableId(id),
        name = name,
        smallBlind = Chips.reconstitute(smallBlind),
        bigBlind = Chips.reconstitute(bigBlind),
        buttonSeatNo = buttonSeatNo,
        seats = seats.associate { it.seatNo to it.toDomain() },
        version = version,
        smallBlindSeatNo = smallBlindSeatNo,
        bigBlindSeatNo = bigBlindSeatNo,
        nextHandAt = nextHandAt,
        joinRequests = joinRequests.associate { it.seatNo to it.toDomain() },
    )

fun HoldemTable.toJpaEntity(): HoldemTableJpaEntity =
    HoldemTableJpaEntity(
        id = id?.value ?: 0,
        name = name,
        smallBlind = smallBlind.amount,
        bigBlind = bigBlind.amount,
        buttonSeatNo = buttonSeatNo,
        smallBlindSeatNo = smallBlindSeatNo,
        bigBlindSeatNo = bigBlindSeatNo,
        nextHandAt = nextHandAt,
        version = version,
    )
