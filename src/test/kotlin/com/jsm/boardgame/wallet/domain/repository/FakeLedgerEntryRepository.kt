package com.jsm.boardgame.wallet.domain.repository

import com.jsm.boardgame.wallet.domain.model.LedgerEntry
import com.jsm.boardgame.wallet.domain.model.LedgerEntryId

/** 서비스 테스트가 공유하는 인메모리 대역. 저장한 엔트리에 번호를 매겨 쌓는다. */
class FakeLedgerEntryRepository : LedgerEntryRepository {
    val stored = mutableListOf<LedgerEntry>()
    private var sequence = 0L

    override fun save(entry: LedgerEntry): LedgerEntry {
        sequence += 1
        val saved = LedgerEntry.reconstitute(
            id = LedgerEntryId(sequence),
            walletId = entry.walletId,
            type = entry.type,
            amount = entry.amount,
            balanceAfter = entry.balanceAfter,
            reference = entry.reference,
            memo = entry.memo,
            occurredAt = entry.occurredAt,
        )
        stored += saved
        return saved
    }
}
