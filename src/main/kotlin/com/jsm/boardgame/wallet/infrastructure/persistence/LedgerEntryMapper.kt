package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.wallet.domain.model.LedgerEntry
import com.jsm.boardgame.wallet.domain.model.LedgerEntryId
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.WalletId

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
