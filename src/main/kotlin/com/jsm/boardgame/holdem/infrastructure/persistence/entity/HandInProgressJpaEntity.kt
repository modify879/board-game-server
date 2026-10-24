package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jsm.boardgame.holdem.domain.model.ActionType
import com.jsm.boardgame.holdem.domain.model.UserId
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
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
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

interface HandInProgressJpaRepository : JpaRepository<HandInProgressJpaEntity, Long>

/**
 * [HandSnapshot] 의 영속 전용 표현. 필드 이름·구조는 대응하는 도메인 스냅샷과 맞추되 타입만 원시로 내린다.
 * [showdownLeaderSeatNo] 와 [BettingRoundJson.lastAggressorSeatNo] 는 nullable + `= null` 기본값이다 —
 * 이 필드가 생기기 전에 저장된 행도 그대로 역직렬화되어야 한다(둘 다 없었으면 null 과 동치이므로 안전하다).
 */
data class HandStateJson(
    val buttonSeatNo: Int,
    // 기본값 null — 이 필드가 없는 예전 행은 블라인드 좌석을 모르는 핸드로 읽는다.
    val smallBlindSeatNo: Int? = null,
    val bigBlindSeatNo: Int? = null,
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
    val showdownLeaderSeatNo: Int? = null,
    // 기본값 false — 이 필드가 없는 예전 행은 런아웃이 아닌 것으로 읽혀 홀카드를 공개하지 않는다(fail closed).
    val runningOut: Boolean = false,
    // 기본값 비어 있음 — 이 필드가 없는 예전 행은 이탈자가 없던 핸드다.
    val departedUserIds: Map<Int, Long> = emptyMap(),
    // 기본값 비어 있음 — 이 필드가 없는 예전 행은 이번 핸드에 행동한 좌석이 없는 것으로 읽는다.
    val lastActions: Map<Int, String> = emptyMap(),
) {
    data class BettingSeatJson(
        val seatNo: Int,
        val stack: Long,
        val committed: Long,
        val status: String,
    )

    // 지운 필드(lastFullLevel)가 이미 저장된 행에 남아 있다 — 모르는 키를 무시하지 않으면 부팅 복구가 그 행에서 터진다
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BettingRoundJson(
        val seats: List<BettingSeatJson>,
        val currentBet: Long,
        val lastRaiseSize: Long,
        val actedSinceLastFullRaise: Set<Int>,
        val toActSeatNo: Int?,
        val lastAggressorSeatNo: Int? = null,
    )
}

fun HandSnapshot.toJson(): HandStateJson = HandStateJson(
    buttonSeatNo = buttonSeatNo,
    smallBlindSeatNo = smallBlindSeatNo,
    bigBlindSeatNo = bigBlindSeatNo,
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
    showdownLeaderSeatNo = showdownLeaderSeatNo,
    runningOut = runningOut,
    departedUserIds = departedUserIds.mapValues { it.value.value },
    lastActions = lastActions.mapValues { it.value.name },
)

private fun HandSnapshot.BettingRoundSnapshot.toJson(): HandStateJson.BettingRoundJson = HandStateJson.BettingRoundJson(
    seats = seats.map { it.toJson() },
    currentBet = currentBet.amount,
    lastRaiseSize = lastRaiseSize.amount,
    actedSinceLastFullRaise = actedSinceLastFullRaise,
    toActSeatNo = toActSeatNo,
    lastAggressorSeatNo = lastAggressorSeatNo,
)

private fun HandSnapshot.BettingSeatSnapshot.toJson(): HandStateJson.BettingSeatJson = HandStateJson.BettingSeatJson(
    seatNo = seatNo,
    stack = stack.amount,
    committed = committed.amount,
    status = status.name,
)

fun HandStateJson.toSnapshot(): HandSnapshot = HandSnapshot(
    buttonSeatNo = buttonSeatNo,
    smallBlindSeatNo = smallBlindSeatNo,
    bigBlindSeatNo = bigBlindSeatNo,
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
    showdownLeaderSeatNo = showdownLeaderSeatNo,
    runningOut = runningOut,
    departedUserIds = departedUserIds.mapValues { UserId(it.value) },
    lastActions = lastActions.mapValues { ActionType.valueOf(it.value) },
)

private fun HandStateJson.BettingRoundJson.toSnapshot(): HandSnapshot.BettingRoundSnapshot = HandSnapshot.BettingRoundSnapshot(
    seats = seats.map { it.toSnapshot() },
    currentBet = Chips.reconstitute(currentBet),
    lastRaiseSize = Chips.reconstitute(lastRaiseSize),
    actedSinceLastFullRaise = actedSinceLastFullRaise,
    toActSeatNo = toActSeatNo,
    lastAggressorSeatNo = lastAggressorSeatNo,
)

private fun HandStateJson.BettingSeatJson.toSnapshot(): HandSnapshot.BettingSeatSnapshot = HandSnapshot.BettingSeatSnapshot(
    seatNo = seatNo,
    stack = Chips.reconstitute(stack),
    committed = Chips.reconstitute(committed),
    status = SeatStatus.valueOf(status),
)
