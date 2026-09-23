package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HandSnapshot
import com.jsm.boardgame.holdem.domain.model.SeatStatus
import com.jsm.boardgame.holdem.domain.model.Street
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * 진행 중 핸드 상태 스냅샷 1행. PK 를 [tableId] 로 둔 이유 — [HandSnapshot] 을 갖는 `Hand` 애그리거트에는
 * 식별자가 없다. `hand_id` 를 따로 만들어 PK 로 두고 `table_id` 에 unique 제약을 거는 설계도 가능하지만,
 * 그러면 도메인에 없는 식별자를 영속 계층이 새로 발급해야 한다. `table_id` 자체를 PK 로 쓰면
 * "테이블당 진행 중 핸드는 하나" 라는 같은 보장을 컬럼 하나로, 새 식별자 없이 얻는다.
 *
 * [state] 는 jsonb 로 저장한다. [HandSnapshot] 을 그대로 넣지 않고 [HandStateJson] 이라는
 * 영속 전용 DTO 를 거치는 이유 — domain 은 Jackson 을 모르고(규칙 2), 도메인 타입에 직렬화 형식을
 * 고정하면 나중에 도메인 규칙을 바꿀 때 이미 저장된 행을 못 읽게 된다. [Chips]·[Card]·enum 은
 * JSON 안에서 각각 `Long`·`"As"` 표기 문자열·이름 문자열이라는 원시 표현으로 내려간다.
 */
@Entity
@Table(name = "holdem_hand_in_progress")
class HandInProgressJpaEntity(
    @Id
    @Column(name = "table_id")
    val tableId: Long,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state", columnDefinition = "jsonb", nullable = false)
    var state: HandStateJson,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

interface HandInProgressJpaRepository : JpaRepository<HandInProgressJpaEntity, Long>

/** [HandSnapshot] 의 영속 전용 표현. 필드 이름·구조는 대응하는 도메인 스냅샷과 맞추되 타입만 원시로 내린다. */
data class HandStateJson(
    val buttonSeatNo: Int,
    val bigBlind: Long,
    val seatNos: List<Int>,
    val street: String,
    val board: List<String>,
    val holeCards: Map<Int, List<String>>,
    val postflopFirstToActSeatNo: Int,
    val startingStacks: Map<Int, Long>,
    val stacks: Map<Int, Long>,
    val statuses: Map<Int, String>,
    val totalContributed: Map<Int, Long>,
    val currentRound: BettingRoundJson?,
) {
    data class BettingSeatJson(
        val seatNo: Int,
        val stack: Long,
        val committed: Long,
        val status: String,
    )

    data class BettingRoundJson(
        val seats: List<BettingSeatJson>,
        val currentBet: Long,
        val lastRaiseSize: Long,
        val lastFullLevel: Long,
        val actedSinceLastFullRaise: Set<Int>,
        val toActSeatNo: Int?,
    )
}

fun HandSnapshot.toJson(): HandStateJson = HandStateJson(
    buttonSeatNo = buttonSeatNo,
    bigBlind = bigBlind.amount,
    seatNos = seatNos,
    street = street.name,
    board = board.map { it.toString() },
    holeCards = holeCards.mapValues { (_, cards) -> cards.map { it.toString() } },
    postflopFirstToActSeatNo = postflopFirstToActSeatNo,
    startingStacks = startingStacks.mapValues { it.value.amount },
    stacks = stacks.mapValues { it.value.amount },
    statuses = statuses.mapValues { it.value.name },
    totalContributed = totalContributed.mapValues { it.value.amount },
    currentRound = currentRound?.toJson(),
)

private fun HandSnapshot.BettingRoundSnapshot.toJson(): HandStateJson.BettingRoundJson = HandStateJson.BettingRoundJson(
    seats = seats.map { it.toJson() },
    currentBet = currentBet.amount,
    lastRaiseSize = lastRaiseSize.amount,
    lastFullLevel = lastFullLevel.amount,
    actedSinceLastFullRaise = actedSinceLastFullRaise,
    toActSeatNo = toActSeatNo,
)

private fun HandSnapshot.BettingSeatSnapshot.toJson(): HandStateJson.BettingSeatJson = HandStateJson.BettingSeatJson(
    seatNo = seatNo,
    stack = stack.amount,
    committed = committed.amount,
    status = status.name,
)

fun HandStateJson.toSnapshot(): HandSnapshot = HandSnapshot(
    buttonSeatNo = buttonSeatNo,
    bigBlind = Chips.reconstitute(bigBlind),
    seatNos = seatNos,
    street = Street.valueOf(street),
    board = board.map { Card.of(it) },
    holeCards = holeCards.mapValues { (_, cards) -> cards.map { Card.of(it) } },
    postflopFirstToActSeatNo = postflopFirstToActSeatNo,
    startingStacks = startingStacks.mapValues { Chips.reconstitute(it.value) },
    stacks = stacks.mapValues { Chips.reconstitute(it.value) },
    statuses = statuses.mapValues { SeatStatus.valueOf(it.value) },
    totalContributed = totalContributed.mapValues { Chips.reconstitute(it.value) },
    currentRound = currentRound?.toSnapshot(),
)

private fun HandStateJson.BettingRoundJson.toSnapshot(): HandSnapshot.BettingRoundSnapshot = HandSnapshot.BettingRoundSnapshot(
    seats = seats.map { it.toSnapshot() },
    currentBet = Chips.reconstitute(currentBet),
    lastRaiseSize = Chips.reconstitute(lastRaiseSize),
    lastFullLevel = Chips.reconstitute(lastFullLevel),
    actedSinceLastFullRaise = actedSinceLastFullRaise,
    toActSeatNo = toActSeatNo,
)

private fun HandStateJson.BettingSeatJson.toSnapshot(): HandSnapshot.BettingSeatSnapshot = HandSnapshot.BettingSeatSnapshot(
    seatNo = seatNo,
    stack = Chips.reconstitute(stack),
    committed = Chips.reconstitute(committed),
    status = SeatStatus.valueOf(status),
)
