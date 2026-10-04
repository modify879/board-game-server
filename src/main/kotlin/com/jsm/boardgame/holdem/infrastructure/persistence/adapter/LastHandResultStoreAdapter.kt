package com.jsm.boardgame.holdem.infrastructure.persistence.adapter

import com.jsm.boardgame.holdem.application.port.LastHandResult
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.LastHandResultJpaEntity
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.LastHandResultJpaRepository
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.toJson
import com.jsm.boardgame.holdem.infrastructure.persistence.entity.toResult
import org.springframework.stereotype.Repository
import java.time.Clock

/**
 * 직전 핸드 공개 결과를 테이블당 1행으로 보관한다. 정산 트랜잭션 안에서 저장하므로 정산과 함께 커밋된다.
 * 번역할 제약이 없어(PK 충돌은 find→save 를 한 스레드가 직렬로 하므로 일어나지 않는다) `saveAndFlush` 로
 * 쓰기만 즉시 반영한다.
 */
@Repository
class LastHandResultStoreAdapter(
    private val jpa: LastHandResultJpaRepository,
    private val clock: Clock,
) : LastHandResultStore {

    override fun find(tableId: TableId): LastHandResult? =
        jpa.findById(tableId.value).orElse(null)?.result?.toResult()

    override fun save(tableId: TableId, result: LastHandResult) {
        val json = result.toJson()
        val entity = jpa.findById(tableId.value).orElse(null)?.apply {
            this.result = json
            updatedAt = clock.instant()
        } ?: LastHandResultJpaEntity(tableId.value, json, clock.instant())
        jpa.saveAndFlush(entity)
    }

    override fun remove(tableId: TableId) {
        val entity = jpa.findById(tableId.value).orElse(null) ?: return
        jpa.delete(entity)
        jpa.flush()
    }
}
