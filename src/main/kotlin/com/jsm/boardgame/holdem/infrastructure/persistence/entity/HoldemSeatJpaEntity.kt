package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Seat
import com.jsm.boardgame.holdem.domain.model.SeatPresence
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 원래는 (table_id, seat_no) 복합키를 생각했지만, 복합키는 unique 제약이 이미 주는 것과 같은
 * 보장을 위해 @IdClass/@EmbeddedId 의례를 끌고 온다 — surrogate id + unique 제약으로 대신한다.
 * uk_holdem_seats_user 는 우연이 아니다: "한 사람, 한 좌석" 을 DB 가 보장하는 자리다
 * (SitDownService 의 사전 체크는 친절한 오류용일 뿐, 동시 요청을 막지 못한다).
 */
@Entity
@Table(
    name = "holdem_seats",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_holdem_seats_table_seat", columnNames = ["table_id", "seat_no"]),
        UniqueConstraint(name = "uk_holdem_seats_user", columnNames = ["user_id"]),
    ],
)
class HoldemSeatJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "table_id", nullable = false)
    var tableId: Long,
    @Column(name = "seat_no", nullable = false)
    var seatNo: Int,
    @Column(name = "user_id", nullable = false)
    var userId: Long,
    @Column(name = "stack", nullable = false)
    var stack: Long,
    @Column(name = "presence", columnDefinition = "text", nullable = false)
    var presence: String,
)

interface HoldemSeatJpaRepository : JpaRepository<HoldemSeatJpaEntity, Long>, KotlinJdslJpqlExecutor {
    fun findAllByTableId(tableId: Long): List<HoldemSeatJpaEntity>
    fun findByUserId(userId: Long): HoldemSeatJpaEntity?
    fun findAllByTableIdIn(tableIds: List<Long>): List<HoldemSeatJpaEntity>
}

fun HoldemSeatJpaEntity.toDomain(): Seat =
    Seat.reconstitute(
        seatNo = seatNo,
        userId = userId,
        stack = Chips.reconstitute(stack),
        presence = SeatPresence.valueOf(presence),
    )

// Seat -> HoldemSeatJpaEntity 매핑은 여기 두지 않는다: 부모 테이블의 DB id 와, 기존 행이 있다면
// 그 행의 id(unique 제약이 스푸리어스하게 튀지 않도록 보존해야 한다)라는 외부 컨텍스트가 필요해서다.
// 다른 엔티티들과 달리 좌석 매핑은 자기 완결적이지 않으므로 어댑터 안의 private fun 으로 둔다.
