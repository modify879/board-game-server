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
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.application.event.WalletCredited
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.jsm.boardgame.wallet.domain.repository.FakeLedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.FakeWalletRepository
import com.jsm.boardgame.wallet.application.event.FakeEventPublisher

private class AdjustFakeUserExistence(private val existingUserIds: MutableSet<Long> = mutableSetOf()) : UserExistence {
    fun register(userId: Long) {
        existingUserIds += userId
    }

    override fun exists(userId: UserId): Boolean = userId.value in existingUserIds
}

private class AdjustFakeAdjustmentKeyRegistry : AdjustmentKeyRegistry {
    private val claimed = mutableSetOf<String>()

    override fun claim(key: String, adminUserId: UserId, targetUserId: UserId, at: Instant) {
        if (!claimed.add(key)) {
            throw AdjustmentAlreadyAppliedException("이미 처리한 조정 키: key=$key")
        }
    }
}

class AdjustWalletBalanceServiceTest {

    private val wallets = FakeWalletRepository()
    private val ledger = FakeLedgerEntryRepository()
    private val userExistence = AdjustFakeUserExistence()
    private val adjustmentKeys = AdjustFakeAdjustmentKeyRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val eventPublisher = FakeEventPublisher()
    private val service = AdjustWalletBalanceService(wallets, ledger, userExistence, adjustmentKeys, clock, eventPublisher)

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
        assertEquals(Money.of(5_000), wallets.findByUserId(UserId(1))!!.balance)
    }

    @Test
    fun `증액이면 WalletCredited 를 발행하고 감액이면 발행하지 않는다`() {
        userExistence.register(1)
        wallets.stored[1] = Wallet.reconstitute(id = WalletId(1), userId = UserId(1), balance = Money.of(10_000))

        service.adjust(AdjustWalletBalanceCommand(targetUserId = 1, amount = -3_000, reason = "오류 회수", adminUserId = 99, idempotencyKey = "key-d"))
        assertEquals(emptyList<Any>(), eventPublisher.events)

        service.adjust(AdjustWalletBalanceCommand(targetUserId = 1, amount = 3_000, reason = "이벤트 보상", adminUserId = 99, idempotencyKey = "key-c"))
        assertEquals(listOf<Any>(WalletCredited(1)), eventPublisher.events)
    }

    @Test
    fun `음수 조정이면 ADMIN_ADJUSTMENT_DEBIT 엔트리가 생기고 잔액이 준다`() {
        userExistence.register(1)
        wallets.stored[1] = Wallet.reconstitute(id = WalletId(1), userId = UserId(1), balance = Money.of(10_000))

        service.adjust(
            AdjustWalletBalanceCommand(targetUserId = 1, amount = -3_000, reason = "오류 회수", adminUserId = 99, idempotencyKey = "key-2"),
        )

        val entry = ledger.stored.single()
        assertEquals(LedgerEntryType.ADMIN_ADJUSTMENT_DEBIT, entry.type)
        assertEquals(Money.of(7_000), wallets.findByUserId(UserId(1))!!.balance)
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

        assertEquals(Money.of(2_000), wallets.findByUserId(UserId(42))!!.balance)
    }

    @Test
    fun `잔액보다 큰 음수 조정이면 INSUFFICIENT_BALANCE`() {
        userExistence.register(1)
        wallets.stored[1] = Wallet.reconstitute(id = WalletId(1), userId = UserId(1), balance = Money.of(1_000))

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
        assertEquals(Money.of(1_000), wallets.findByUserId(UserId(1))!!.balance)
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

        assertEquals(Money.of(3_000), wallets.findByUserId(UserId(1))!!.balance)
        assertEquals(2, ledger.stored.size)
    }
}
