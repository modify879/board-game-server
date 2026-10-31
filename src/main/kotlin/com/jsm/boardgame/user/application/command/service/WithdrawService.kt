package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.support.CurrentPasswordVerifier
import com.jsm.boardgame.user.application.command.usecase.LogoutUseCase
import com.jsm.boardgame.user.application.command.usecase.WithdrawCommand
import com.jsm.boardgame.user.application.command.usecase.WithdrawUseCase
import com.jsm.boardgame.user.application.exception.WithdrawalSeatedAtTableException
import com.jsm.boardgame.user.application.exception.WalletLockContendedException
import com.jsm.boardgame.user.application.exception.WithdrawalWalletNotEmptyException
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.application.port.ProfileImageStorage
import com.jsm.boardgame.user.application.port.TablePresence
import com.jsm.boardgame.user.application.port.WalletHoldings
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.ProfileImageKey
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant

/**
 * 회원 탈퇴(소프트 삭제). 바깥 트랜잭션은 열지 않는다 — 비밀번호 해싱·Redis·파일을 하는 동안 DB 커넥션을 쥐지 않게,
 * 사전 조건과 저장만 짧은 트랜잭션 하나로 묶는다.
 *
 * 그 트랜잭션은 **지갑 락을 먼저** 잡는다. 바이인·충전·환전 요청도 같은 지갑 락을 잡고 그 뒤 활성 여부를 보므로,
 * 동시에 들어온 착석은 락 전에 커밋됐으면 아래 좌석 확인에 걸리고 아니면 락을 기다렸다가 탈퇴 상태에서 떨어진다.
 * 지갑이 없던 사용자의 첫 지갑 생성과 엇갈리면(`WalletLockContendedException`) 트랜잭션째 한 번 다시 한다.
 *
 * 순서와 중간 실패 시 남는 상태:
 * | 단계 | 실패하면 |
 * |---|---|
 * | 1. 조회·비밀번호 확인 | 아무것도 안 바뀐다 |
 * | 2. 트랜잭션: 지갑 락·확인 → 좌석·대기열 확인 → withdraw + save | 롤백. 아무것도 안 바뀐다 |
 * | 3. 프로필 이미지 삭제 | 로그만 남는다. 고아 파일(ChangeProfileImageService 참고) |
 * | 4. 세션 종료 → 실패 카운터 초기화 | 오류 응답이지만 탈퇴는 끝났다. 탈퇴한 사용자는 조회되지 않아 남은 토큰은 갱신도 안 되고 API 도 못 쓴다 |
 */
@Service
class WithdrawService(
    private val users: UserRepository,
    private val verifier: CurrentPasswordVerifier,
    private val tablePresence: TablePresence,
    private val walletHoldings: WalletHoldings,
    private val profileImageStorage: ProfileImageStorage,
    private val logout: LogoutUseCase,
    private val loginAttemptLimiter: LoginAttemptLimiter,
    private val transactions: TransactionOperations,
    private val clock: Clock,
) : WithdrawUseCase {

    override fun withdraw(command: WithdrawCommand) {
        val userId = UserId(command.userId)
        val user = users.findById(userId)
            ?: throw UserNotFoundException("탈퇴하려는 사용자를 찾을 수 없음: userId=${command.userId}")
        verifier.verify(user, command.password)

        // 첫 지갑 생성과 엇갈려 깨진 트랜잭션은 한 번만 새로 연다 — 그때는 상대 지갑 행이 커밋돼 있어 잠글 수 있다.
        val previousImageKey = try {
            closeAccount(user, userId)
        } catch (e: WalletLockContendedException) {
            log.info("탈퇴 지갑 락 경합으로 재시도: userId={}", command.userId)
            closeAccount(user, userId)
        }
        previousImageKey?.let { key ->
            runCatching { profileImageStorage.delete(key) }
                .onFailure { log.warn("탈퇴 사용자의 프로필 이미지 삭제 실패: key={}", key.value, it) }
        }
        logout.logout(command.userId)
        loginAttemptLimiter.reset(user.username)
        log.info("회원 탈퇴: userId={}, reason=WITHDRAWAL", command.userId)
    }

    private fun closeAccount(user: User, userId: UserId): ProfileImageKey? = transactions.execute {
        val walletBusy = walletHoldings.lockAndHasBalanceOrPendingRequests(userId) // 락이 먼저다 — 클래스 KDoc
        if (tablePresence.isAtAnyTable(userId)) {
            throw WithdrawalSeatedAtTableException("테이블에 앉아 있거나 대기 중이라 탈퇴할 수 없다: userId=${userId.value}")
        }
        if (walletBusy) {
            throw WithdrawalWalletNotEmptyException("지갑에 잔액이나 처리 중인 요청이 있어 탈퇴할 수 없다: userId=${userId.value}")
        }
        user.withdraw(Instant.now(clock)).also { users.save(user) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WithdrawService::class.java)
    }
}
