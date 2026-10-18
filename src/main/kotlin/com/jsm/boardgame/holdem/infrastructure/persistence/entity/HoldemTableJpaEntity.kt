package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TablePolicy
import com.jsm.boardgame.holdem.domain.model.TableId
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

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
    // 설정이 바뀌어도 이미 열린 테이블의 규칙은 그대로여야 해 테이블 행이 들고 있다. 기존 행은 정책이 상수였던 때의 값이다.
    @Column(name = "min_buy_in_big_blinds", nullable = false, columnDefinition = "integer not null default 20")
    var minBuyInBigBlinds: Int,
    @Column(name = "max_buy_in_big_blinds", nullable = false, columnDefinition = "integer not null default 100")
    var maxBuyInBigBlinds: Int,
    @Column(name = "button_seat_no")
    var buttonSeatNo: Int?,
    @Column(name = "small_blind_seat_no")
    var smallBlindSeatNo: Int?,
    @Column(name = "big_blind_seat_no")
    var bigBlindSeatNo: Int?,
    @Column(name = "closed_at")
    var closedAt: Instant?,
)

interface HoldemTableJpaRepository : JpaRepository<HoldemTableJpaEntity, Long>, KotlinJdslJpqlExecutor {
    fun existsByIdAndClosedAtIsNull(id: Long): Boolean
}

fun HoldemTableJpaEntity.toDomain(seats: List<HoldemSeatJpaEntity>): HoldemTable =
    HoldemTable.reconstitute(
        id = TableId(id),
        name = name,
        policy = TablePolicy.reconstitute(smallBlind, bigBlind, minBuyInBigBlinds, maxBuyInBigBlinds),
        buttonSeatNo = buttonSeatNo,
        seats = seats.associate { it.seatNo to it.toDomain() },
        smallBlindSeatNo = smallBlindSeatNo,
        bigBlindSeatNo = bigBlindSeatNo,
        closedAt = closedAt,
    )

fun HoldemTable.toJpaEntity(): HoldemTableJpaEntity =
    HoldemTableJpaEntity(
        id = id?.value ?: 0,
        name = name,
        smallBlind = smallBlind.amount,
        bigBlind = bigBlind.amount,
        minBuyInBigBlinds = policy.minBuyInBigBlinds,
        maxBuyInBigBlinds = policy.maxBuyInBigBlinds,
        buttonSeatNo = buttonSeatNo,
        smallBlindSeatNo = smallBlindSeatNo,
        bigBlindSeatNo = bigBlindSeatNo,
        closedAt = closedAt,
    )
