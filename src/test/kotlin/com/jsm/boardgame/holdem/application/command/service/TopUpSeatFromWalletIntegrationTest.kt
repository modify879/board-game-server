package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.wallet.application.command.usecase.ApproveDepositRequestCommand
import com.jsm.boardgame.wallet.application.command.usecase.ApproveDepositRequestUseCase
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositCommand
import com.jsm.boardgame.wallet.application.command.usecase.RequestDepositUseCase
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.UserId as WalletUserId
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 충전 승인 → WalletCredited → 커밋 후 리스너 → TableExecutor → 스택 합산까지 실제 배선으로 검증한다.
 * 핸드가 없는 테이블에 앉아 있는 사용자의 충전분이 그 자리에서 DB 좌석 스택으로 옮겨져야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TopUpSeatFromWalletIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var tables: HoldemTableRepository

    @Autowired
    private lateinit var wallets: WalletRepository

    @Autowired
    private lateinit var requestDeposit: RequestDepositUseCase

    @Autowired
    private lateinit var approveDeposit: ApproveDepositRequestUseCase

    /** fk_wallets_user 때문에 실제 users 행을 먼저 만든다. */
    private fun signUp(): Long {
        val suffix = UniqueNames.suffix()
        val username = "u" + suffix
        val result = mockMvc.perform(
            post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"username":"$username","password":"password123","passwordConfirm":"password123","nickname":"n${suffix}"}""",
                ),
        ).andExpect(status().isCreated).andReturn()
        return result.response.getHeader("Location")!!.substringAfterLast("/").toLong()
    }

    @Test
    fun `핸드가 없는 테이블에 앉아 있으면 충전 승인 즉시 지갑 잔액 전부가 좌석 스택에 합쳐진다`() {
        val userId = signUp()
        var table = tables.save(HoldemTable.create("topup-${UUID.randomUUID().toString().take(8)}"))
        table.sitDown(UserId(userId), Chips.of(10_000))
        table = tables.save(table)

        val requestId = requestDeposit.request(RequestDepositCommand(userId, 10_000))
        approveDeposit.approve(ApproveDepositRequestCommand(requestId.value, adminUserId = userId, creditedAmount = null))

        assertThat(tables.findById(table.id!!)!!.seatOf(UserId(userId))!!.stack).isEqualTo(Chips.of(20_000))
        assertThat(wallets.findByUserId(WalletUserId(userId))!!.balance).isEqualTo(Money.ZERO)
    }
}
