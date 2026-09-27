package com.jsm.boardgame.wallet.infrastructure.persistence.entity

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
 * 관리자 조정 요청의 Idempotency-Key 1건. 같은 키의 두 번째 claim 은 이 테이블의 PK
 * (wallet_adjustment_keys_pkey) 위반으로 막힌다.
 *
 * assigned id(`idempotencyKey`)라 Spring Data 가 새 행인지 스스로 판단하지 못한다.
 * `Long = 0` 버전으로 판단하게 하던 자리를 [Persistable] 로 대체했다 — [isNew] 를 생성 시
 * `true` 로 시작해 저장·조회 이후 `false` 로 내리면, 최초 저장은 항상 `persist`(INSERT)로 가고
 * 이미 있는 행을 `merge` 로 보내 거부당하는 일이 없다. (같은 이유로 `HandInProgressJpaEntity`
 * 도 같은 패턴을 쓴다 — 그쪽은 다른 브랜치에서 이 방식으로 바뀌는 중이다.)
 */
@Entity
@Table(name = "wallet_adjustment_keys")
class WalletAdjustmentKeyJpaEntity(
    @Id
    @Column(name = "idempotency_key", columnDefinition = "text")
    val idempotencyKey: String,

    @Column(name = "admin_user_id", nullable = false)
    val adminUserId: Long,

    @Column(name = "target_user_id", nullable = false)
    val targetUserId: Long,

    @Column(name = "claimed_at", nullable = false)
    val claimedAt: Instant,
) : Persistable<String> {

    @Transient
    private var new: Boolean = true

    override fun getId(): String = idempotencyKey
    override fun isNew(): Boolean = new

    @PostPersist
    @PostLoad
    fun markNotNew() {
        new = false
    }
}

interface WalletAdjustmentKeyJpaRepository : JpaRepository<WalletAdjustmentKeyJpaEntity, String>
