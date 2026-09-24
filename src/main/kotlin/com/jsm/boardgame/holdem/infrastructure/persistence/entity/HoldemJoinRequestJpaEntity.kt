package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.JoinRequest
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@Entity
@Table(
    name = "holdem_join_requests",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_holdem_join_requests_table_seat", columnNames = ["table_id", "seat_no"]),
        UniqueConstraint(name = "uk_holdem_join_requests_user", columnNames = ["user_id"]),
    ],
)
class HoldemJoinRequestJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "table_id", nullable = false)
    var tableId: Long,
    @Column(name = "seat_no", nullable = false)
    var seatNo: Int,
    @Column(name = "user_id", nullable = false)
    var userId: Long,
    @Column(name = "buy_in", nullable = false)
    var buyIn: Long,
    @Column(name = "post_blind_immediately", nullable = false)
    var postBlindImmediately: Boolean,
    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant,
)

interface HoldemJoinRequestJpaRepository : JpaRepository<HoldemJoinRequestJpaEntity, Long> {
    fun findAllByTableId(tableId: Long): List<HoldemJoinRequestJpaEntity>
    fun findByUserId(userId: Long): HoldemJoinRequestJpaEntity?
}

fun HoldemJoinRequestJpaEntity.toDomain(): JoinRequest =
    JoinRequest(
        userId = userId,
        seatNo = seatNo,
        buyIn = Chips.reconstitute(buyIn),
        postBlindImmediately = postBlindImmediately,
        requestedAt = requestedAt,
    )
