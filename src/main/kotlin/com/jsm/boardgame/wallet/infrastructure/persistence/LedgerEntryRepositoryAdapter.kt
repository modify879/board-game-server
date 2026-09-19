package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.wallet.domain.model.LedgerEntry
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import org.springframework.stereotype.Repository

// 원장은 append-only 라 번역할 제약이 없다.
@Repository
class LedgerEntryRepositoryAdapter(
    private val jpa: LedgerEntryJpaRepository,
) : LedgerEntryRepository {

    override fun save(entry: LedgerEntry): LedgerEntry =
        jpa.save(entry.toJpaEntity()).toDomain()
}
