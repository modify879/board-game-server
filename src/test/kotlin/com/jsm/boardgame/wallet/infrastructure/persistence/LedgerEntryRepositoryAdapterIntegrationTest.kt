package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 저장한 원장 엔트리를 DB 에서 다시 읽어 enum ↔ text 매핑이 양방향으로 맞는지 검증한다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LedgerEntryRepositoryAdapterIntegrationTest {

    @Autowired
    private lateinit var wallets: WalletRepository

    @Autowired
    private lateinit var ledgerEntries: LedgerEntryRepository

    @Autowired
    private lateinit var ledgerEntriesJpa: LedgerEntryJpaRepository

    @Test
    fun `저장한 엔트리를 다시 읽으면 type amount balanceAfter reference memo 가 보존된다`() {
        val wallet = wallets.save(Wallet.open(userId = System.nanoTime()))
        val reference = LedgerReference(LedgerReferenceType.DEPOSIT_REQUEST, 7)
        val entry = wallet.record(
            type = LedgerEntryType.DEPOSIT,
            amount = Money.of(1_000),
            reference = reference,
            memo = "테스트 충전",
            at = Instant.parse("2026-01-01T00:00:00Z"),
        )

        val saved = ledgerEntries.save(entry)
        val reloaded = ledgerEntriesJpa.findById(saved.id!!.value).get().toDomain()

        assertEquals(LedgerEntryType.DEPOSIT, reloaded.type)
        assertEquals(Money.of(1_000), reloaded.amount)
        assertEquals(Money.of(1_000), reloaded.balanceAfter)
        assertEquals(reference, reloaded.reference)
        assertEquals("테스트 충전", reloaded.memo)
    }
}
