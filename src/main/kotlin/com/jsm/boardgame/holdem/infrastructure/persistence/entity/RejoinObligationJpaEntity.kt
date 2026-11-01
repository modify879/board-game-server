package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@Embeddable
data class RejoinObligationId(
    @Column(name = "user_id")
    val userId: Long,
    @Column(name = "table_id")
    val tableId: Long,
) : Serializable

/**
 * 떠난 테이블별 가져간 금액 1행. PK 는 (user_id, table_id) 이고 [HandInProgressJpaEntity] 처럼 Persistable 로 isNew 를 직접 알린다.
 * 예전 `holdem_recent_departure`(PK user_id)는 ddl-auto update 가 PK 를 못 바꿔 새 테이블로 옮겼다 — 옛 테이블은 더 쓰지 않는다.
 */
@Entity
@Table(name = "holdem_rejoin_obligation")
class RejoinObligationJpaEntity(
    @EmbeddedId
    val key: RejoinObligationId,
    @Column(name = "amount", nullable = false)
    var amount: Long,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) : Persistable<RejoinObligationId> {
    @Transient
    private var new: Boolean = true

    override fun getId(): RejoinObligationId = key
    override fun isNew(): Boolean = new

    @PostLoad
    @PostPersist
    fun markNotNew() {
        new = false
    }
}

interface RejoinObligationJpaRepository : JpaRepository<RejoinObligationJpaEntity, RejoinObligationId> {
    fun findByKeyUserIdAndExpiresAtAfter(userId: Long, now: Instant): List<RejoinObligationJpaEntity>

    // 파생 삭제 쿼리는 트랜잭션이 필요하다 — QA 리셋 서비스는 트랜잭션 밖이라 여기서 연다(호출자가 있으면 합류).
    @Transactional
    fun deleteByKeyUserId(userId: Long)
}
