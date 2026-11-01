package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TablePolicyView
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandInProgressJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandInProgressJpaRepository
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HoldemSeatJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HoldemSeatJpaRepository
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HoldemTableJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HoldemTableJpaRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository

@Repository
class HoldemTableQueryRepositoryAdapter(
    private val tableJpa: HoldemTableJpaRepository,
    private val seatJpa: HoldemSeatJpaRepository,
    private val handJpa: HandInProgressJpaRepository,
) : HoldemTableQueryRepository {

    override fun findSeatOf(userId: Long): SeatLocation? =
        seatJpa.findAll {
            selectNew<SeatLocation>(
                path(HoldemSeatJpaEntity::tableId),
                path(HoldemSeatJpaEntity::seatNo),
                path(HoldemSeatJpaEntity::stack),
            ).from(entity(HoldemSeatJpaEntity::class))
                .where(path(HoldemSeatJpaEntity::userId).eq(userId))
        }.firstOrNull()

    override fun findPolicyOf(tableId: Long): TablePolicyView? =
        tableJpa.findAll {
            selectNew<TablePolicyView>(
                path(HoldemTableJpaEntity::smallBlind),
                path(HoldemTableJpaEntity::bigBlind),
                path(HoldemTableJpaEntity::minBuyInBigBlinds),
                path(HoldemTableJpaEntity::maxBuyInBigBlinds),
            ).from(entity(HoldemTableJpaEntity::class))
                .where(and(path(HoldemTableJpaEntity::id).eq(tableId), path(HoldemTableJpaEntity::closedAt).isNull()))
        }.firstOrNull()

    override fun existsHandInProgress(tableId: Long): Boolean =
        handJpa.findAll {
            select(path(HandInProgressJpaEntity::tableId)).from(entity(HandInProgressJpaEntity::class))
                .where(path(HandInProgressJpaEntity::tableId).eq(tableId))
        }.isNotEmpty()

    override fun findTableIdsWithHandInProgress(tableIds: Collection<Long>): Set<Long> =
        if (tableIds.isEmpty()) emptySet()
        else handJpa.findAll {
            select(path(HandInProgressJpaEntity::tableId)).from(entity(HandInProgressJpaEntity::class))
                .where(path(HandInProgressJpaEntity::tableId).`in`(tableIds))
        }.filterNotNull().toSet()

    // 집계 JDSL(count + groupBy + join) 선례가 이 저장소에 없어, 그 문법을 추측하는 대신
    // 페이지당 2번의 조회로 간다: 테이블 페이지 하나 + 그 페이지의 테이블 id 들로 좌석을 한 번에
    // 묶어 세는 것. N+1 은 아니다(페이지 크기와 무관하게 쿼리 2개).
    //
    // 플레이스홀더 값은 value() 가 아니라 intLiteral() 을 쓴다 — value() 는 바인드 파라미터를
    // 만드는데, SELECT NEW 생성자 표현식 안에서는 Hibernate 가 바인드 파라미터의 타입을
    // 추론하지 못해 "Missing constructor for type 'TableSummaryView'" 로 죽는다(통합 테스트로
    // 실제 DB 에 붙여보기 전까지 드러나지 않았다). intLiteral() 은 JPQL 에 리터럴로 박혀 타입이
    // 명확하다.
    override fun findAllTables(pageable: Pageable): Page<TableSummaryView> {
        val page = tableJpa.findPage(pageable) {
            selectNew<TableSummaryView>(
                path(HoldemTableJpaEntity::id),
                path(HoldemTableJpaEntity::name),
                intLiteral(0),
                intLiteral(HoldemTable.MAX_SEATS),
                booleanLiteral(false),
                path(HoldemTableJpaEntity::smallBlind),
                path(HoldemTableJpaEntity::bigBlind),
                path(HoldemTableJpaEntity::minBuyInBigBlinds),
                path(HoldemTableJpaEntity::maxBuyInBigBlinds),
            ).from(entity(HoldemTableJpaEntity::class))
                .where(path(HoldemTableJpaEntity::closedAt).isNull())
                .orderBy(path(HoldemTableJpaEntity::id).asc())
        }

        val occupiedCounts = seatJpa.findAllByTableIdIn(page.content.map { it.tableId })
            .groupingBy { it.tableId }
            .eachCount()

        return page.map { it.copy(occupiedSeats = occupiedCounts[it.tableId] ?: 0) }
    }

    override fun findEmptyTableIds(): List<Long> =
        tableJpa.findAll {
            select(path(HoldemTableJpaEntity::id)).from(entity(HoldemTableJpaEntity::class))
                .where(
                    and(
                        path(HoldemTableJpaEntity::closedAt).isNull(),
                        path(HoldemTableJpaEntity::id).notIn(
                            select(path(HoldemSeatJpaEntity::tableId)).from(entity(HoldemSeatJpaEntity::class)).asSubquery(),
                        ),
                    ),
                )
        }.filterNotNull()
}
