package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.common.persistence.violatedConstraint
import com.jsm.boardgame.holdem.application.exception.HandInProgressException
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.domain.exception.ConcurrentTableUpdateException
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandInProgressJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.HandInProgressJpaRepository
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.toJson
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.toSnapshot
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Repository
import java.time.Clock

// Postgres 가 PK 에 이름을 안 줘도 자동으로 붙이는 이름(`<table>_pkey`). `@Id` 컬럼에 별도 제약 이름을 주지 않았다.
private const val CONSTRAINT_HAND_PK = "holdem_hand_in_progress_pkey"

/**
 * 진행 중 핸드를 PostgreSQL 행 하나(테이블당 최대 1행)로 보관한다.
 *
 * Redis 가 아니라 PostgreSQL 인 이유 — 핸드 종료 정산([HandSettler.settle] 이 `HoldemTableRepository.save`
 * 로 스택을 반영하는 것)과 이 저장소의 [remove] 가 **같은 `@Transactional` 트랜잭션 안에서 커밋**돼야 한다.
 * 저장소가 갈리면 그 사이에 프로세스가 죽었을 때 "정산은 됐는데 진행 중 핸드 행은 남아" 있는 상태가 생겨,
 * 다음 조각(부팅 복구)이 같은 핸드를 한 번 더 복구해 스택을 두 번 바꾸게 된다. 같은 DB, 같은 트랜잭션이면
 * 이 창이 아예 없다.
 *
 * 쓰기 순서 — 핸드 시작 → INSERT([save]), 액션마다 → UPDATE state([save]), 핸드 종료 → 정산 커밋 + DELETE.
 * [HandSettler.settle] 이 `tables.save(table)` 다음에 `handStore.remove(tableId)` 를 부르고,
 * 그 둘을 부르는 `StartHandService.start`/`PlayActionService.play` 가 이미 `@Transactional` 이라
 * 정산과 삭제는 이미 한 트랜잭션 안에 있다 — 여기서 새로 트랜잭션을 열 필요가 없다.
 *
 * 상태 저장이 브로드캐스트보다 먼저 커밋된다 — `HandBroadcaster` 는
 * `@TransactionalEventListener`(기본 phase `AFTER_COMMIT`)라 이 트랜잭션이 커밋된 뒤에만 나간다.
 * 순서가 뒤집히면(브로드캐스트가 먼저) 브로드캐스트 직후 죽었을 때 플레이어가 이미 화면으로 본 액션을
 * 서버가 기억하지 못해, 재접속 시 화면이 그 액션 이전으로 되감긴다.
 *
 * `synchronous_commit` 을 끄지 않는다 — 데이터가 깨지지는 않지만 최근 커밋 몇 건이 크래시로 사라질 수
 * 있고, 하필 그 커밋들이 환불액을 결정하는 핸드 상태다. unlogged table 도 쓰지 않는다 — 크래시 후
 * 재시작 시 PostgreSQL 이 자동으로 TRUNCATE 해, 정확히 복구가 필요한 순간에 행이 비어 있게 된다.
 *
 * `version` 이 있는 이유 — 같은 행을 find→변경→save 로 다루는 쓰기가 둘이다. HTTP 요청을 타는
 * `PlayActionService` 와, HTTP 호출자 없이 스케줄러 스레드에서 도는 타이머 만료 경로
 * (`ExpireTurnService`). READ_COMMITTED 에서는 둘이 겹치면 버전 체크 없이 나중 커밋이 앞 커밋을
 * 조용히 덮어써 액션 하나가 사라진다 — `@Version` 은 그 덮어쓰기를 낙관적 락 예외로 드러낸다.
 * nullable 인 이유 — `table_id` 는 채번이 아니라 애그리거트가 정하는 assigned id 라, Spring Data
 * 의 `isNew()` 판단이 id 만으로는 안 되고 존재하는 version 프로퍼티로 넘어간다. `null` 이 "아직
 * 저장 전" 이라 INSERT 로 가고, 값이 생기면 UPDATE(`merge`) 로 간다. 버전을 primitive/non-null 로
 * 두면 assigned id 는 항상 non-null 이라 Spring Data 가 항상 `merge` 를 타게 되는데, 아직 행이 없는
 * 첫 저장까지 `merge` 로 가면 Hibernate 가 "없는 행을 merge" 라며 예외를 던진다.
 */
@Repository
class HandInProgressStoreAdapter(
    private val handJpa: HandInProgressJpaRepository,
    private val shuffler: Shuffler,
    private val clock: Clock,
) : HandStore {

    // 덱을 저장하지 않으므로(규칙 6, HandSnapshot 참고) 복원 시 남은 카드를 shuffler 로 다시 섞는다.
    override fun find(tableId: TableId): Hand? {
        val entity = handJpa.findById(tableId.value).orElse(null) ?: return null
        return Hand.reconstitute(entity.state.toSnapshot(), shuffler)
    }

    override fun save(tableId: TableId, hand: Hand) {
        val json = hand.snapshot().toJson()
        val existing = handJpa.findById(tableId.value).orElse(null)
        val entity = if (existing != null) {
            existing.apply {
                state = json
                updatedAt = clock.instant()
            }
        } else {
            HandInProgressJpaEntity(tableId = tableId.value, state = json, updatedAt = clock.instant())
        }
        try {
            handJpa.saveAndFlush(entity)
        } catch (e: OptimisticLockingFailureException) {
            throw ConcurrentTableUpdateException("낙관적 락 충돌: ${e.message}")
        } catch (e: DataIntegrityViolationException) {
            throw translateHand(e)
        }
    }

    private fun translateHand(e: DataIntegrityViolationException): RuntimeException {
        val constraintName = e.violatedConstraint(CONSTRAINT_HAND_PK) ?: return e
        return when (constraintName) {
            CONSTRAINT_HAND_PK -> HandInProgressException("기본키 제약 위반: $constraintName")
            else -> e
        }
    }

    // 이 핸드가 한 번도 handStore.save() 를 거치지 않고 바로 끝난 경우(딜 직후 전원 올인 등)
    // 대응하는 행이 아예 없을 수 있다 — findById 가 비어 있으면 지울 것도 없으니 그대로 끝낸다.
    override fun remove(tableId: TableId) {
        val entity = handJpa.findById(tableId.value).orElse(null) ?: return
        try {
            handJpa.delete(entity)
            // 여기서 flush 해야 버전이 걸린 DELETE 의 낙관적 락 충돌이 이 메서드의 catch 안에서 드러난다.
            // flush 를 안 하면 충돌은 바깥 트랜잭션 커밋 시점에야 터져 ConcurrentTableUpdateException 으로
            // 못 옮긴다.
            handJpa.flush()
        } catch (e: OptimisticLockingFailureException) {
            throw ConcurrentTableUpdateException("낙관적 락 충돌: ${e.message}")
        }
    }

    override fun findAllInProgress(): List<TableId> = handJpa.findAll().map { TableId(it.tableId) }
}
