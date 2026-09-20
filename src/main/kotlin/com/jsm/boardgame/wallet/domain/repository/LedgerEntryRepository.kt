package com.jsm.boardgame.wallet.domain.repository

import com.jsm.boardgame.wallet.domain.model.LedgerEntry

/**
 * 원장은 무한히 늘어나는 컬렉션이라 Wallet 애그리거트 안에 넣으면
 * 지갑을 읽을 때마다 전부 끌고 온다. 그래서 별도 출력 포트로 둔다.
 */
interface LedgerEntryRepository {
    fun save(entry: LedgerEntry): LedgerEntry
}
