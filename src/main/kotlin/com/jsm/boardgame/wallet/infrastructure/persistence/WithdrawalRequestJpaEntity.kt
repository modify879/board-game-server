package com.jsm.boardgame.wallet.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(
    name = "withdrawal_requests",
    indexes = [
        Index(name = "idx_withdrawal_requests_user", columnList = "user_id, id"),
        Index(name = "idx_withdrawal_requests_status", columnList = "status, id"),
    ],
)
class WithdrawalRequestJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "amount", nullable = false)
    var amount: Long,

    // BankAccount 는 @Embedded 를 쓰지 않고 컬럼 세 개로 편다 — 도메인/JPA 분리.
    @Column(name = "bank_name", columnDefinition = "text", nullable = false)
    var bankName: String,

    @Column(name = "account_number", columnDefinition = "text", nullable = false)
    var accountNumber: String,

    @Column(name = "account_holder", columnDefinition = "text", nullable = false)
    var accountHolder: String,

    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant,

    @Column(name = "status", columnDefinition = "text", nullable = false)
    var status: String,

    @Column(name = "processed_by")
    var processedBy: Long?,

    @Column(name = "processed_at")
    var processedAt: Instant?,

    @Column(name = "rejection_reason", columnDefinition = "text")
    var rejectionReason: String?,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
