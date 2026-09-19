package com.jsm.boardgame.wallet.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "wallet_ledger_entries",
    indexes = [Index(name = "idx_wallet_ledger_entries_wallet", columnList = "wallet_id, id")],
)
class LedgerEntryJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "wallet_id", nullable = false)
    var walletId: Long,

    @Column(name = "type", columnDefinition = "text", nullable = false)
    var type: String,

    @Column(name = "amount", nullable = false)
    var amount: Long,

    @Column(name = "balance_after", nullable = false)
    var balanceAfter: Long,

    @Column(name = "reference_type", columnDefinition = "text", nullable = false)
    var referenceType: String,

    @Column(name = "reference_id", nullable = false)
    var referenceId: Long,

    @Column(name = "memo", columnDefinition = "text")
    var memo: String?,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant,
)
