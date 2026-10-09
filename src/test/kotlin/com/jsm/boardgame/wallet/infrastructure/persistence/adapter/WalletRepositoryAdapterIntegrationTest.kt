package com.jsm.boardgame.wallet.infrastructure.persistence.adapter

import com.jsm.boardgame.UniqueNames
import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.wallet.domain.exception.ConcurrentWalletUpdateException
import com.jsm.boardgame.wallet.domain.exception.InsufficientBalanceException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.exception.WalletOwnerNotFoundException
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `WalletRepositoryAdapter.save()` 가 DB CHECK/unique 제약, 낙관적 락 충돌을
 * 도메인 예외로 번역하는 경로를 검증한다.
 *
 * 여기서 검증하는 것은 도메인 검사(`Wallet.record` 의 잔액 부족 검사)가 아니라
 * DB 제약이 실제로 잡고 어댑터가 번역하는가다. 그러려면 도메인 검사를 우회해야 하므로,
 * 검증하지 않는 `reconstitute` 로 음수 잔액 지갑을 직접 만들어 저장한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WalletRepositoryAdapterIntegrationTest {

    @Autowired
    private lateinit var wallets: WalletRepository

    @Autowired
    private lateinit var users: UserRepository

    // fk_wallets_user 가 실제로 걸려 있으므로 존재하지 않는 userId 로는 저장할 수 없다 —
    // 가짜 nanoTime 대신 실제 사용자 행을 만들어 그 id 를 쓴다.
    private fun uniqueUserId(): Long {
        val suffix = UniqueNames.suffix()
        val user = User.register(
            username = Username.of("u$suffix"),
            passwordHash = PasswordHash("hashed-password-value"),
            nickname = Nickname.of("n" + suffix),
        )
        return users.save(user).id!!.value
    }

    @Test
    fun `음수 잔액을 저장하면 InsufficientBalanceException 으로 번역된다`() {
        val negative = Wallet.reconstitute(
            id = WalletId(0),
            userId = UserId(uniqueUserId()),
            balance = Money.reconstitute(-100),
        )

        val e = assertFailsWith<InsufficientBalanceException> { wallets.save(negative) }
        assertEquals(WalletErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
    }

    // saveAndFlush 가 아니면 이 테스트가 실패한다: save 만 쓰면 UPDATE 의 CHECK 제약 위반이
    // 트랜잭션 커밋 시점까지 미뤄져 어댑터의 catch 를 지나쳐 버린다.
    // 이 테스트가 그 함정의 회귀 방지다.
    @Test
    fun `UPDATE 경로에서도 음수 잔액이 InsufficientBalanceException 으로 잡힌다`() {
        val saved = wallets.save(Wallet.open(userId = UserId(uniqueUserId())))

        val negative = Wallet.reconstitute(
            id = saved.id!!,
            userId = saved.userId,
            balance = Money.reconstitute(-100),
        )

        val e = assertFailsWith<InsufficientBalanceException> { wallets.save(negative) }
        assertEquals(WalletErrorCode.INSUFFICIENT_BALANCE, e.errorCode)
    }

    @Test
    fun `같은 userId 로 지갑을 두 번 open 해 저장하면 ConcurrentWalletUpdateException`() {
        val userId = uniqueUserId()
        wallets.save(Wallet.open(userId = UserId(userId)))

        val e = assertFailsWith<ConcurrentWalletUpdateException> {
            wallets.save(Wallet.open(userId = UserId(userId)))
        }
        assertEquals(WalletErrorCode.CONCURRENT_WALLET_UPDATE, e.errorCode)
    }

    @Test
    fun `저장 후 findByUserId 가 잔액을 그대로 돌려준다`() {
        val userId = uniqueUserId()
        val opened = wallets.save(Wallet.open(userId = UserId(userId)))
        wallets.save(
            Wallet.reconstitute(id = opened.id!!, userId = UserId(userId), balance = Money.of(500)),
        )

        val found = wallets.findByUserId(UserId(userId))

        assertEquals(Money.of(500), found?.balance)
    }

    // 이 테스트가 실패하면 data.sql 이 안 돈 것이다 — fk_wallets_user 가 실제로 걸려 있고
    // 어댑터가 그 위반을 도메인 예외로 번역하는지를 함께 검증한다.
    @Test
    fun `존재하지 않는 userId 로 지갑을 저장하면 WalletOwnerNotFoundException 으로 번역된다`() {
        val nonExistentUserId = 987_654_321L

        val e = assertFailsWith<WalletOwnerNotFoundException> {
            wallets.save(Wallet.open(userId = UserId(nonExistentUserId)))
        }
        assertEquals(WalletErrorCode.WALLET_OWNER_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `findOrOpen 도 존재하지 않는 userId 면 WalletOwnerNotFoundException 으로 번역된다`() {
        val nonExistentUserId = 987_654_322L

        val e = assertFailsWith<WalletOwnerNotFoundException> {
            wallets.findOrOpen(UserId(nonExistentUserId))
        }
        assertEquals(WalletErrorCode.WALLET_OWNER_NOT_FOUND, e.errorCode)
    }
}
