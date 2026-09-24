package com.jsm.boardgame.wallet.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * 관리자 조정 요청의 Idempotency-Key 1건. 같은 키의 두 번째 claim 은 이 테이블의 PK
 * (wallet_adjustment_keys_pkey) 위반으로 막힌다.
 *
 * [version] 을 nullable 로 둔 이유는 HandInProgressJpaEntity 와 같다 — idempotencyKey 가
 * assigned id 라, `Long = 0` 이면 Spring Data 가 첫 저장도 merge 로 보내 없는 행 merge 로 거부한다.
 * null = 새 행 → persist 로 판단하게 하려면 version 이 nullable 이어야 한다.
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

    @Version
    @Column(name = "version")
    var version: Long? = null,
)

interface WalletAdjustmentKeyJpaRepository : JpaRepository<WalletAdjustmentKeyJpaEntity, String>
