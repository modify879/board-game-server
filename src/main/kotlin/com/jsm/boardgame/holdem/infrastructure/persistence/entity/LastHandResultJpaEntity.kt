package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import com.jsm.boardgame.holdem.application.port.LastHandResult
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * 직전 핸드의 공개 결과 1행. PK 를 [tableId] 로 둔 이유는 [HandInProgressJpaEntity] 와 같다.
 * [result] 는 [LastHandResult] 를 그대로 넣지 않고 영속 전용 [LastHandResultJson] 을 거친다 — domain·application
 * 타입에 직렬화 형식을 고정하면 타입을 바꿀 때 이미 저장된 행을 못 읽게 된다.
 */
@Entity
@Table(name = "holdem_last_hand_result")
class LastHandResultJpaEntity(
    @Id
    @Column(name = "table_id")
    val tableId: Long,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb", nullable = false)
    var result: LastHandResultJson,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) : Persistable<Long> {
    @Transient
    private var new: Boolean = true

    override fun getId(): Long = tableId
    override fun isNew(): Boolean = new

    @PostLoad
    @PostPersist
    fun markNotNew() {
        new = false
    }
}

interface LastHandResultJpaRepository : JpaRepository<LastHandResultJpaEntity, Long>

data class LastHandResultJson(
    val board: List<String>,
    val payouts: List<PayoutJson>,
    val shownHands: List<ShownHandJson>,
) {
    data class PayoutJson(val seatNo: Int, val userId: Long, val amount: Long)
    data class ShownHandJson(val seatNo: Int, val userId: Long, val holeCards: List<String>, val category: String)
}

fun LastHandResult.toJson() = LastHandResultJson(
    board,
    payouts.map { LastHandResultJson.PayoutJson(it.seatNo, it.userId, it.amount) },
    shownHands.map { LastHandResultJson.ShownHandJson(it.seatNo, it.userId, it.holeCards, it.category) },
)

fun LastHandResultJson.toResult() = LastHandResult(
    board,
    payouts.map { LastHandResult.Payout(it.seatNo, it.userId, it.amount) },
    shownHands.map { LastHandResult.ShownHand(it.seatNo, it.userId, it.holeCards, it.category) },
)
