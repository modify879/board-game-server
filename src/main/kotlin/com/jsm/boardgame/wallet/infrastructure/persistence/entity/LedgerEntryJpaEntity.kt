package com.jsm.boardgame.wallet.infrastructure.persistence.entity

import com.jsm.boardgame.wallet.domain.model.LedgerEntry
import com.jsm.boardgame.wallet.domain.model.LedgerEntryId
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
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

interface LedgerEntryJpaRepository :
    JpaRepository<LedgerEntryJpaEntity, Long>,
    KotlinJdslJpqlExecutor

fun LedgerEntryJpaEntity.toDomain(): LedgerEntry =
    LedgerEntry.reconstitute(
        id = LedgerEntryId(id),
        walletId = WalletId(walletId),
        type = LedgerEntryType.valueOf(type),
        amount = Money.reconstitute(amount),
        balanceAfter = Money.reconstitute(balanceAfter),
        reference = LedgerReference(LedgerReferenceType.valueOf(referenceType), referenceId),
        memo = memo,
        occurredAt = occurredAt,
    )

fun LedgerEntry.toJpaEntity(): LedgerEntryJpaEntity =
    LedgerEntryJpaEntity(
        id = id?.value ?: 0,
        walletId = walletId.value,
        type = type.name,
        amount = amount.amount,
        balanceAfter = balanceAfter.amount,
        referenceType = reference.type.name,
        referenceId = reference.id,
        memo = memo,
        occurredAt = occurredAt,
    )
