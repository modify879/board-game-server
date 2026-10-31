package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.LogoutUseCase
import com.jsm.boardgame.user.application.command.usecase.WithdrawCommand
import com.jsm.boardgame.user.application.command.usecase.WithdrawUseCase
import com.jsm.boardgame.user.application.exception.WithdrawalSeatedAtTableException
import com.jsm.boardgame.user.application.exception.WithdrawalWalletNotEmptyException
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.application.port.ProfileImageStorage
import com.jsm.boardgame.user.application.port.TablePresence
import com.jsm.boardgame.user.application.port.WalletHoldings
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 회원 탈퇴(소프트 삭제). 트랜잭션을 열지 않는다 — DB 는 `findById`(읽기)와 `save`(단독 커밋)뿐이고 나머지는
 * Redis·파일·타 컨텍스트 조회라 DB 커넥션을 쥔 채 기다리지 않게 한다.
 *
 * 순서와 중간 실패 시 남는 상태:
 * | 단계 | 실패하면 |
 * |---|---|
 * | 1. 조회·비밀번호 확인·사전 조건(좌석·지갑) | 아무것도 안 바뀐다 |
 * | 2. withdraw + save (DB, 단독 커밋) | 아무것도 안 바뀐다 |
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
    private val clock: Clock,
) : WithdrawUseCase {

    override fun withdraw(command: WithdrawCommand) {
        val userId = UserId(command.userId)
        val user = users.findById(userId)
            ?: throw UserNotFoundException("탈퇴하려는 사용자를 찾을 수 없음: userId=${command.userId}")
        verifier.verify(user, command.password)

        // ponytail: 이 검사와 탈퇴는 홀덤·지갑 쪽과 원자적이지 않다 — 그 사이에 착석할 수 있다.
        // 탈퇴 직후 세션을 끊어 창이 아주 좁다. 문제가 되면 탈퇴 상태를 그쪽이 거부하도록 한다.
        if (tablePresence.isAtAnyTable(userId)) {
            throw WithdrawalSeatedAtTableException("테이블에 앉아 있거나 대기 중이라 탈퇴할 수 없다: userId=${command.userId}")
        }
        if (walletHoldings.hasBalanceOrPendingRequests(userId)) {
            throw WithdrawalWalletNotEmptyException("지갑에 잔액이나 처리 중인 요청이 있어 탈퇴할 수 없다: userId=${command.userId}")
        }

        val previousImageKey = user.withdraw(Instant.now(clock))
        users.save(user)
        previousImageKey?.let { key ->
            runCatching { profileImageStorage.delete(key) }
                .onFailure { log.warn("탈퇴 사용자의 프로필 이미지 삭제 실패: key={}", key.value, it) }
        }
        logout.logout(command.userId)
        loginAttemptLimiter.reset(user.username)
        log.info("회원 탈퇴: userId={}, reason=WITHDRAWAL", command.userId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(WithdrawService::class.java)
    }
}
