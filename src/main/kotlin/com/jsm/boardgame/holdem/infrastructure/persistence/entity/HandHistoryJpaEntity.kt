package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * 핸드 결과 요약 1행. 플레이어 행([HandHistoryPlayerJpaEntity])은 연관관계 없이 [id] 를 값으로 가리킨다 —
 * FK(ON DELETE CASCADE)는 data.sql 이 건다. board 는 카드를 공백으로 이은 문자열("Ah Kd 7c"), 없으면 빈 문자열.
 */
@Entity
@Table(name = "holdem_hand_history", indexes = [
        Index(name = "idx_hand_history_ended_at", columnList = "ended_at"),
        Index(name = "idx_hand_history_table", columnList = "table_id"),
    ])
class HandHistoryJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "table_id", nullable = false)
    val tableId: Long,
    @Column(name = "ended_at", nullable = false)
    val endedAt: Instant,
    @Column(name = "button_seat_no", nullable = false)
    val buttonSeatNo: Int,
    @Column(name = "board", columnDefinition = "text", nullable = false)
    val board: String,
)

@Entity
@Table(name = "holdem_hand_history_player", indexes = [
        Index(name = "idx_hand_history_player_user", columnList = "user_id"),
        Index(name = "idx_hand_history_player_hand", columnList = "hand_id"),
    ])
class HandHistoryPlayerJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "hand_id", nullable = false)
    val handId: Long,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "seat_no", nullable = false)
    val seatNo: Int,
    @Column(name = "starting_stack", nullable = false)
    val startingStack: Long,
    @Column(name = "ending_stack", nullable = false)
    val endingStack: Long,
    @Column(name = "payout", nullable = false)
    val payout: Long,
    @Column(name = "hole_cards", columnDefinition = "text", nullable = false)
    val holeCards: String,
    @Column(name = "shown", nullable = false)
    var shown: Boolean,
)

interface HandHistoryJpaRepository : JpaRepository<HandHistoryJpaEntity, Long>, KotlinJdslJpqlExecutor {
    fun findTopByTableIdOrderByIdDesc(tableId: Long): HandHistoryJpaEntity?

    fun findAllByEndedAtBefore(cutoff: Instant, pageable: Pageable): List<HandHistoryJpaEntity>
}

interface HandHistoryPlayerJpaRepository : JpaRepository<HandHistoryPlayerJpaEntity, Long>, KotlinJdslJpqlExecutor {
    fun findByHandIdAndUserId(handId: Long, userId: Long): HandHistoryPlayerJpaEntity?
}
