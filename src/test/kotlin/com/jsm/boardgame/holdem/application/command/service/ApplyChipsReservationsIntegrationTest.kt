package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.holdem.application.command.usecase.ApplyChipsReservationsCommand
import com.jsm.boardgame.holdem.application.command.usecase.ApplyChipsReservationsUseCase
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceUseCase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `SeatChipsReservationApplier` 가 진짜 Spring 프록시를 통해 REQUIRES_NEW 로 도는지 검증한다.
 *
 * 목 없이 지갑 이체만 골라 실패시킬 방법이 없다(적용액이 지갑 잔액으로 이미 잘려 이체는 실패할 수 없다).
 * 그래서 바깥 트랜잭션 안에서 적용을 부르고 바깥을 롤백시킨다 — REQUIRES_NEW 면 좌석 적용은 이미 별도로
 * 커밋돼 남고, 그냥 합류했다면 같이 사라진다.
 */
@IntegrationTest
class ApplyChipsReservationsIntegrationTest {

    @Autowired private lateinit var tables: HoldemTableRepository
    @Autowired private lateinit var applyReservations: ApplyChipsReservationsUseCase
    @Autowired private lateinit var walletTransfer: WalletTransfer
    @Autowired private lateinit var adjustWallet: AdjustWalletBalanceUseCase
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tx: TransactionTemplate

    @Test
    fun `바깥 트랜잭션이 롤백돼도 좌석 단위 칩 예약 적용은 REQUIRES_NEW 로 이미 커밋돼 남는다`() {
        val suffix = UniqueNames.suffix()
        val userId = users.save(
            User.register(Username.of("u$suffix"), PasswordHash("hashed-password-value"), Nickname.of("n$suffix")),
        ).id!!.value
        adjustWallet.adjust(AdjustWalletBalanceCommand(userId, 15_000, "테스트 지급", 1L, UUID.randomUUID().toString()))

        val table = HoldemTable.create("reserve-${System.nanoTime()}", TestTablePolicy)
        table.sitDown(UserId(userId), Chips.of(10_000))
        val tableId = tables.save(table).id!!
        walletTransfer.toGame(UserId(userId), 10_000, tableId, "holdem")
        tables.findById(tableId)!!.also {
            it.reserveChips(UserId(userId), Chips.of(5_000))
            tables.save(it)
        }

        tx.execute { status ->
            applyReservations.apply(ApplyChipsReservationsCommand(tableId.value))
            status.setRollbackOnly()
        }

        val seat = tables.findById(tableId)!!.seatOf(UserId(userId))!!
        assertEquals(15_000L, seat.stack.amount)
        assertNull(seat.chipsReservation)
    }
}
