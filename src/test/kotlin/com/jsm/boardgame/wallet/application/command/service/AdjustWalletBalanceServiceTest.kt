package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.exception.AdjustmentAlreadyAppliedException
import com.jsm.boardgame.wallet.application.exception.IdempotencyKeyInvalidException
import com.jsm.boardgame.wallet.application.port.AdjustmentKeyRegistry
import com.jsm.boardgame.wallet.application.port.UserExistence
import com.jsm.boardgame.wallet.domain.exception.InsufficientBalanceException
import com.jsm.boardgame.wallet.domain.exception.InvalidAdjustmentException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.exception.WalletOwnerNotFoundException
import com.jsm.boardgame.wallet.domain.model.LedgerEntry
import com.jsm.boardgame.wallet.domain.model.LedgerEntryId
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class AdjustFakeWalletRepository : WalletRepository {
    val stored = mutableMapOf<Long, Wallet>()
    private var sequence = 0L

    override fun findByUserId(userId: Long): Wallet? = stored[userId]

    override fun save(wallet: Wallet): Wallet {
        val saved = if (wallet.id == null) {
            sequence += 1
            Wallet.reconstitute(id = WalletId(sequence), userId = wallet.userId, balance = wallet.balance, version = wallet.version)
        } else {
            wallet
        }
        stored[wallet.userId] = saved
        return saved
    }

    override fun findOrOpen(userId: Long): Wallet = findByUserId(userId) ?: save(Wallet.open(userId))
}

private class AdjustFakeUserExistence(private val existingUserIds: MutableSet<Long> = mutableSetOf()) : UserExistence {
    fun register(userId: Long) {
        existingUserIds += userId
    }

    override fun exists(userId: Long): Boolean = userId in existingUserIds
}

private class AdjustFakeAdjustmentKeyRegistry : AdjustmentKeyRegistry {
    private val claimed = mutableSetOf<String>()

    override fun claim(key: String, adminUserId: Long, targetUserId: Long, at: Instant) {
        if (!claimed.add(key)) {
            throw AdjustmentAlreadyAppliedException("이미 처리한 조정 키: key=$key")
        }
    }
}

private class AdjustFakeLedgerEntryRepository : LedgerEntryRepository {
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

class AdjustWalletBalanceServiceTest {

    private val wallets = AdjustFakeWalletRepository()
    private val ledger = AdjustFakeLedgerEntryRepository()
    private val userExistence = AdjustFakeUserExistence()
    private val adjustmentKeys = AdjustFakeAdjustmentKeyRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = AdjustWalletBalanceService(wallets, ledger, userExistence, adjustmentKeys, clock)

    @Test
    fun `양수 조정이면 ADMIN_ADJUSTMENT_CREDIT 엔트리가 생기고 memo 와 reference 가 채워진다`() {
        userExistence.register(1)

        service.adjust(
            AdjustWalletBalanceCommand(targetUserId = 1, amount = 5_000, reason = "이벤트 보상", adminUserId = 99, idempotencyKey = "key-1"),
        )

        val entry = ledger.stored.single()
        assertEquals(LedgerEntryType.ADMIN_ADJUSTMENT_CREDIT, entry.type)
        assertEquals("이벤트 보상", entry.memo)
        assertEquals(LedgerReferenceType.ADMIN_ADJUSTMENT, entry.reference.type)
        assertEquals(99L, entry.reference.id)
        assertEquals(Money.of(5_000), wallets.findByUserId(1)!!.balance)
    }

    @Test
    fun `음수 조정이면 ADMIN_ADJUSTMENT_DEBIT 엔트리가 생기고 잔액이 준다`() {
        userExistence.register(1)
        wallets.stored[1] = Wallet.reconstitute(id = WalletId(1), userId = 1, balance = Money.of(10_000), version = 0)

        service.adjust(
            AdjustWalletBalanceCommand(targetUserId = 1, amount = -3_000, reason = "오류 회수", adminUserId = 99, idempotencyKey = "key-2"),
        )

        val entry = ledger.stored.single()
        assertEquals(LedgerEntryType.ADMIN_ADJUSTMENT_DEBIT, entry.type)
        assertEquals(Money.of(7_000), wallets.findByUserId(1)!!.balance)
    }

    @Test
    fun `amount 가 0 이면 ADJUSTMENT_AMOUNT_INVALID`() {
        val e = assertFailsWith<InvalidAdjustmentException> {
            service.adjust(AdjustWalletBalanceCommand(targetUserId = 1, amount = 0, reason = "사유", adminUserId = 99, idempotencyKey = "key-3"))
        }
        assertEquals(WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `100원 배수가 아니면 ADJUSTMENT_AMOUNT_INVALID`() {
        val e = assertFailsWith<InvalidAdjustmentException> {
            service.adjust(
                AdjustWalletBalanceCommand(targetUserId = 1, amount = 1_050, reason = "사유", adminUserId = 99, idempotencyKey = "key-4"),
            )
        }
        assertEquals(WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `amount 가 Long_MIN_VALUE 면 ADJUSTMENT_AMOUNT_INVALID (abs 오버플로 회귀 방지)`() {
        val e = assertFailsWith<InvalidAdjustmentException> {
            service.adjust(
                AdjustWalletBalanceCommand(targetUserId = 1, amount = Long.MIN_VALUE, reason = "사유", adminUserId = 99, idempotencyKey = "key-5"),
            )
        }
        assertEquals(WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `사유가 blank 면 ADJUSTMENT_REASON_BLANK`() {
        val e = assertFailsWith<InvalidAdjustmentException> {
            service.adjust(
                AdjustWalletBalanceCommand(targetUserId = 1, amount = 1_000, reason = "   ", adminUserId = 99, idempotencyKey = "key-6"),
            )
        }
        assertEquals(WalletErrorCode.ADJUSTMENT_REASON_BLANK, e.errorCode)
    }

    @Test
    fun `지갑이 없던 사용자에게 양수 조정을 하면 지갑이 만들어지고 잔액이 채워진다`() {
        userExistence.register(42)

        service.adjust(
            AdjustWalletBalanceCommand(targetUserId = 42, amount = 2_000, reason = "사유", adminUserId = 99, idempotencyKey = "key-7"),
        )

        assertEquals(Money.of(2_000), wallets.findByUserId(42)!!.balance)
    }

    @Test
    fun `잔액보다 큰 음수 조정이면 INSUFFICIENT_BALANCE`() {
        userExistence.register(1)
        wallets.stored[1] = Wallet.reconstitute(id = WalletId(1), userId = 1, balance = Money.of(1_000), version = 0)

        val e = assertFailsWith<InsufficientBalanceException> {
            service.adjust(
                AdjustWalletBalanceCommand(targetUserId = 1, amount = -5_000, reason = "사유", adminUserId = 99, idempotencyKey = "key-8"),
            )
        }
        assertEquals(WalletErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
    }

    @Test
    fun `존재하지 않는 사용자를 대상으로 조정하면 WALLET_OWNER_NOT_FOUND 이고 지갑이 만들어지지 않는다`() {
        val e = assertFailsWith<WalletOwnerNotFoundException> {
            service.adjust(
                AdjustWalletBalanceCommand(targetUserId = 999, amount = 1_000, reason = "사유", adminUserId = 99, idempotencyKey = "key-9"),
            )
        }

        assertEquals(WalletErrorCode.WALLET_OWNER_NOT_FOUND, e.errorCode)
        assertTrue(wallets.stored.isEmpty())
    }

    @Test
    fun `같은 idempotencyKey 로 두 번 조정하면 두 번째는 ADJUSTMENT_ALREADY_APPLIED 이고 잔액은 한 번만 반영된다`() {
        userExistence.register(1)
        val command = AdjustWalletBalanceCommand(
            targetUserId = 1,
            amount = 1_000,
            reason = "사유",
            adminUserId = 99,
            idempotencyKey = "dup-key",
        )

        service.adjust(command)
        val e = assertFailsWith<AdjustmentAlreadyAppliedException> { service.adjust(command) }

        assertEquals(WalletErrorCode.ADJUSTMENT_ALREADY_APPLIED, e.errorCode)
        assertEquals(Money.of(1_000), wallets.findByUserId(1)!!.balance)
        assertEquals(1, ledger.stored.size)
    }

    @Test
    fun `idempotencyKey 가 null 이면 IDEMPOTENCY_KEY_INVALID`() {
        userExistence.register(1)

        val e = assertFailsWith<IdempotencyKeyInvalidException> {
            service.adjust(AdjustWalletBalanceCommand(targetUserId = 1, amount = 1_000, reason = "사유", adminUserId = 99, idempotencyKey = null))
        }

        assertEquals(WalletErrorCode.IDEMPOTENCY_KEY_INVALID, e.errorCode)
    }

    @Test
    fun `idempotencyKey 가 blank 면 IDEMPOTENCY_KEY_INVALID`() {
        userExistence.register(1)

        val e = assertFailsWith<IdempotencyKeyInvalidException> {
            service.adjust(
                AdjustWalletBalanceCommand(targetUserId = 1, amount = 1_000, reason = "사유", adminUserId = 99, idempotencyKey = "   "),
            )
        }

        assertEquals(WalletErrorCode.IDEMPOTENCY_KEY_INVALID, e.errorCode)
    }

    @Test
    fun `idempotencyKey 가 101 코드포인트면 IDEMPOTENCY_KEY_INVALID`() {
        userExistence.register(1)
        val tooLong = "a".repeat(101)

        val e = assertFailsWith<IdempotencyKeyInvalidException> {
            service.adjust(
                AdjustWalletBalanceCommand(targetUserId = 1, amount = 1_000, reason = "사유", adminUserId = 99, idempotencyKey = tooLong),
            )
        }

        assertEquals(WalletErrorCode.IDEMPOTENCY_KEY_INVALID, e.errorCode)
    }

    @Test
    fun `서로 다른 idempotencyKey 두 개면 조정이 둘 다 적용된다`() {
        userExistence.register(1)

        service.adjust(
            AdjustWalletBalanceCommand(targetUserId = 1, amount = 1_000, reason = "사유1", adminUserId = 99, idempotencyKey = "key-a"),
        )
        service.adjust(
            AdjustWalletBalanceCommand(targetUserId = 1, amount = 2_000, reason = "사유2", adminUserId = 99, idempotencyKey = "key-b"),
        )

        assertEquals(Money.of(3_000), wallets.findByUserId(1)!!.balance)
        assertEquals(2, ledger.stored.size)
    }
}
