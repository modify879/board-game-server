package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelHandUseCase
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceUseCase
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.UserId as WalletUserId
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 만료 폴드로 핸드 도중 기립한 좌석이 있는 핸드를 취소해도 롤백되지 않고, 그 사용자가 시작 스택 전부를
 * 지갑으로 돌려받는지 실제 DB 로 검증한다(이탈 기록이 스냅샷 JSON 을 왕복해야 성립한다).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CancelHandIntegrationTest {

    @Autowired private lateinit var tables: HoldemTableRepository
    @Autowired private lateinit var handStore: HandStore
    @Autowired private lateinit var expireTurn: ExpireTurnUseCase
    @Autowired private lateinit var cancelHand: CancelHandUseCase
    @Autowired private lateinit var walletTransfer: WalletTransfer
    @Autowired private lateinit var adjustWallet: AdjustWalletBalanceUseCase
    @Autowired private lateinit var wallets: WalletRepository
    @Autowired private lateinit var users: UserRepository

    // fk_wallets_user, fk_holdem_seats_user 때문에 실제 사용자 행을 만든다.
    private fun userWithBuyIn(buyIn: Long): Long {
        val suffix = UniqueNames.suffix()
        val userId = users.save(
            User.register(Username.of("u$suffix"), PasswordHash("hashed-password-value"), Nickname.of("n" + suffix)),
        ).id!!.value
        adjustWallet.adjust(AdjustWalletBalanceCommand(userId, buyIn, "테스트 지급", 1L, UUID.randomUUID().toString()))
        return userId
    }

    @Test
    fun `만료 폴드로 기립한 좌석이 있어도 취소는 성공하고 그 사용자는 시작 스택 전부를 지갑으로 돌려받는다`() {
        val userIds = List(3) { userWithBuyIn(10_000) }
        var table = HoldemTable.create("cancel-${System.nanoTime()}")
        userIds.forEach { table.sitDown(UserId(it), Chips.of(10_000)) }
        table.moveButtonToNextOccupiedSeat()
        val tableId = tables.save(table).id!!
        userIds.forEach { walletTransfer.toGame(UserId(it), 10_000, tableId, "holdem") }
        table = tables.findById(tableId)!!

        val seatNos = table.occupiedSeats().map { it.seatNo }.sorted()
        fun next(from: Int) = seatNos[(seatNos.indexOf(from) + 1) % seatNos.size]
        val button = table.buttonSeatNo!!
        val sb = next(button)
        val hand = Hand.start(
            seatNos.associateWith { Chips.of(10_000) }, button, sb, next(sb), table.smallBlind, table.bigBlind, Shuffler { it },
        )
        hand.act(button, BettingAction.Call)
        handStore.save(tableId, hand)
        val departedUserId = table.seatAt(sb)!!.userId.value
        assertEquals(sb, hand.toActSeatNo)

        // SB 는 콜해야 해서 체크할 수 없다 -> 만료 폴드 + 즉시 기립. 잔여 스택은 이미 지갑으로 돌아갔다.
        expireTurn.expire(ExpireTurnCommand(tableId.value, sb))
        assertNull(tables.findById(tableId)!!.seatAt(sb))
        assertEquals(Money.of(10_000L - table.smallBlind.amount), wallets.findByUserId(WalletUserId(departedUserId))!!.balance)

        cancelHand.cancel(CancelHandCommand(tableId.value))

        assertNull(handStore.find(tableId))
        assertEquals(Money.of(10_000L), wallets.findByUserId(WalletUserId(departedUserId))!!.balance)
        val saved = tables.findById(tableId)!!
        assertEquals(2, saved.occupiedSeats().size)
        assertEquals(listOf(10_000L, 10_000L), saved.occupiedSeats().map { it.stack.amount })
    }
}
