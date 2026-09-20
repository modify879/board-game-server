package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.RefreshTokenCommand
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.domain.exception.InvalidRefreshTokenException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 파일 최상위 클래스는 private 이어도 JVM 클래스명이 그대로 노출되므로,
 * 같은 패키지의 다른 테스트 파일과 이름이 겹치면 실제로 충돌한다(Redeclaration).
 * 그래서 Refresh 접두어로 파일 간 이름 충돌을 피한다.
 */
private class RefreshFakeAuthTokenIssuer : AuthTokenIssuer {
    private var counter = 0
    var lastRole: UserRole? = null
        private set

    override fun issue(userId: Long, role: UserRole): IssuedTokens {
        lastRole = role
        counter += 1
        val refreshToken = "rt-$counter"
        val accessToken = "at-$counter"
        return IssuedTokens(
            accessToken = accessToken,
            accessTokenId = "jti-$counter",
            accessTokenExpiresAt = Instant.now().plusSeconds(1_800),
            refreshToken = refreshToken,
            refreshTokenExpiresAt = Instant.now().plusSeconds(1_209_600),
        )
    }
}

/**
 * 실제 [com.jsm.boardgame.user.infrastructure.security.adapter.RedisAuthSessionStore] 와 같은 유예 규칙을
 * 흉내 낸다 — 직전 토큰은 한 세대만 기억하고, 그 세대에 한해서만 [refreshReuseGrace] 동안
 * 재사용을 허용한다. 두 세대 전 토큰은 애초에 기억하지 않으므로 유예와 무관하게 거부된다.
 *
 * [AuthSession] 에는 previousRefreshToken 이 없다 — 직전 칸에 무엇을 넣을지는 저장소가 정한다.
 * [rotate] 가 성공적으로 교체할 때 다음 직전 칸에는 언제나 "이번 회전으로 밀려난 현재 토큰"이
 * 들어간다(제시된 토큰이 아니다). 실제 저장소(RedisAuthSessionStore)와 같은 규칙이어야
 * 이 페이크로 [RefreshTokenService] 의 재사용 탐지 시나리오를 신뢰성 있게 검증할 수 있다.
 */
private class RefreshInMemoryAuthSessionStore(
    private val refreshReuseGrace: Duration = Duration.ofSeconds(30),
) : AuthSessionStore {
    private data class StoredSession(
        val session: AuthSession,
        val previousRefreshToken: String?,
        val graceExpiresAt: Instant,
    )

    private val sessions = mutableMapOf<Long, StoredSession>()
    private val blacklist = mutableMapOf<String, Instant>()
    // 인덱스는 회전해도 지우지 않는다 — 지우면 아래 재사용 탐지 테스트가 무의미해진다.
    private val refreshTokenIndex = mutableMapOf<String, Long>()

    override fun start(userId: Long, session: AuthSession) {
        // 로그인은 항상 새 세션이다 — 직전 칸은 비운다.
        sessions[userId] = StoredSession(session, previousRefreshToken = null, graceExpiresAt = Instant.EPOCH)
        refreshTokenIndex[session.refreshToken] = userId
    }

    override fun userIdForRefreshToken(refreshToken: String): Long? = refreshTokenIndex[refreshToken]

    // AuthSessionStore 포트 계약이 아니다 — 유예 규칙까지 반영해 세션 상태를 들여다보는 페이크 전용 검사용 헬퍼다.
    fun matchesRefreshToken(userId: Long, refreshToken: String): Boolean {
        val stored = sessions[userId] ?: return false
        if (stored.session.refreshToken == refreshToken) return true

        val previous = stored.previousRefreshToken ?: return false
        if (previous != refreshToken) return false

        return Instant.now().isBefore(stored.graceExpiresAt)
    }

    override fun currentAccessTokenId(userId: Long): String? = sessions[userId]?.session?.accessTokenId

    override fun clear(userId: Long) {
        sessions.remove(userId)
    }

    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) {
        blacklist[accessTokenId] = expiresAt
    }

    override fun isAccessTokenBlacklisted(accessTokenId: String): Boolean = blacklist.containsKey(accessTokenId)

    /**
     * 실제 [com.jsm.boardgame.user.infrastructure.security.adapter.RedisAuthSessionStore.rotate] 와 같은 규칙으로
     * 대조와 교체를 한 번에 수행한다 — 현재 토큰이거나 유예 안의 직전 토큰이면 교체하고, 아니면 세션을
     * 건드리지 않은 채 Mismatch 를 돌려준다. 통과하면 다음 직전 칸에는 이번에 밀려난 현재 토큰
     * (stored.session.refreshToken)이 들어간다 — 제시된 토큰이 현재 칸과 일치했든 유예 중인 직전
     * 칸과 일치했든 상관없다. 호출자가 넘기는 [next] 에는 애초에 직전 칸을 정할 수단이 없다.
     */
    override fun rotate(userId: Long, presentedRefreshToken: String, next: AuthSession): RotationResult {
        val stored = sessions[userId] ?: return RotationResult.Mismatch

        val matches = if (stored.session.refreshToken == presentedRefreshToken) {
            true
        } else {
            val previous = stored.previousRefreshToken
            previous != null && previous == presentedRefreshToken && Instant.now().isBefore(stored.graceExpiresAt)
        }

        if (!matches) return RotationResult.Mismatch

        val previousAccessTokenId = stored.session.accessTokenId
        sessions[userId] = StoredSession(
            session = next,
            previousRefreshToken = stored.session.refreshToken,
            graceExpiresAt = Instant.now().plus(refreshReuseGrace),
        )
        refreshTokenIndex[next.refreshToken] = userId
        return RotationResult.Rotated(previousAccessTokenId)
    }
}

private class RefreshFakeUserRepository : UserRepository {
    private val stored = mutableMapOf<Long, User>()

    fun put(userId: Long, role: UserRole) {
        stored[userId] = User.reconstitute(
            id = UserId(userId),
            username = Username.reconstitute("user_$userId"),
            passwordHash = PasswordHash("hashed"),
            nickname = Nickname.reconstitute("닉네임$userId"),
            profileImageKey = null,
            role = role,
        )
    }

    override fun findByUsername(username: Username): User? = stored.values.find { it.username == username }
    override fun findById(id: UserId): User? = stored[id.value]
    override fun existsByUsername(username: Username): Boolean = stored.values.any { it.username == username }
    override fun existsByNickname(nickname: Nickname): Boolean = stored.values.any { it.nickname == nickname }
    override fun save(user: User): User = user
}

class RefreshTokenServiceTest {

    private val tokenIssuer = RefreshFakeAuthTokenIssuer()
    private val sessions = RefreshInMemoryAuthSessionStore()
    private val users = RefreshFakeUserRepository()
    private val service = RefreshTokenService(tokenIssuer, sessions, users, Duration.ofMinutes(30), Clock.systemUTC())

    private fun loginSession(userId: Long, role: UserRole = UserRole.USER): IssuedTokens {
        users.put(userId, role)
        val tokens = tokenIssuer.issue(userId, role)
        sessions.start(userId, AuthSession(tokens.accessTokenId, tokens.refreshToken, tokens.refreshTokenExpiresAt))
        return tokens
    }

    @Test
    fun `유효한 리프레시 토큰으로 갱신하면 새 토큰이 발급된다`() {
        val userId = 1L
        val first = loginSession(userId)

        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))

        assertNotEquals(first.refreshToken, rotated.refreshToken)
        assertNotEquals(first.accessToken, rotated.accessToken)
        // 직전 한 세대는 응답 유실 재시도를 위한 유예 대상이라 아직 통과한다 —
        // "완전히 무효화됨"은 두 세대 전부터다. 아래 두 세대 전 테스트가 그 경계를 검증한다.
        assertTrue(sessions.matchesRefreshToken(userId, first.refreshToken))
    }

    @Test
    fun `유예 안에서 회전된 옛 리프레시 토큰으로 재시도하면 세션이 폐기되지 않고 계속 갱신할 수 있다`() {
        val userId = 6L
        val first = loginSession(userId)
        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))

        // 회전 응답이 유실돼 클라이언트가 같은(옛) 토큰으로 재시도하는 상황을 재현한다.
        val retried = service.refresh(RefreshTokenCommand(first.refreshToken))

        assertNotEquals(rotated.refreshToken, retried.refreshToken)
        assertNotNull(sessions.currentAccessTokenId(userId))

        // 세션이 폐기되지 않았으므로 최신 토큰으로 계속 갱신할 수 있다.
        val next = service.refresh(RefreshTokenCommand(retried.refreshToken))
        assertNotEquals(retried.refreshToken, next.refreshToken)
    }

    @Test
    fun `유예 재시도 후에는 밀려난 직전 회전 토큰으로 정상 클라이언트가 계속 갱신할 수 있다`() {
        val userId = 9L
        val first = loginSession(userId)
        // 정상 회전: 클라이언트 A 가 rotated 를 받아 든다.
        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))

        // 이미 소모된 first 가 다시 제시된다(응답 유실 재시도, 혹은 first 를 가로챈 다른 주체) —
        // 유예 통과.
        val retriedByOther = service.refresh(RefreshTokenCommand(first.refreshToken))
        assertNotEquals(rotated.refreshToken, retriedByOther.refreshToken)

        // 클라이언트 A 는 여전히 rotated 를 들고 있다 — 이번 회전으로 "밀려난 현재 토큰"은
        // rotated 이므로 직전 칸에 들어가 있어야 하고, 유예 안에서 정상적으로 갱신에 성공해야 한다.
        // 직전 칸에 "제시된 토큰"(first)을 그대로 기록하는 버그가 있었다면 rotated 는 두 칸
        // 어디에도 없어 여기서 재사용으로 오인되어 세션째 폐기됐을 것이다.
        val nextForA = service.refresh(RefreshTokenCommand(rotated.refreshToken))
        assertNotEquals(rotated.refreshToken, nextForA.refreshToken)
        assertNotNull(sessions.currentAccessTokenId(userId))
    }

    @Test
    fun `유예 중인 같은 직전 토큰을 반복 제시하면 두 번째부터는 거부된다`() {
        val userId = 10L
        val first = loginSession(userId)
        service.refresh(RefreshTokenCommand(first.refreshToken))

        // 첫 번째 유예 재시도는 통과한다(응답 유실 후 정상 재시도, 혹은 탈취자의 최초 시도).
        service.refresh(RefreshTokenCommand(first.refreshToken))

        // 같은 first 를 다시 제시한다 — 직전 칸에 "제시된 토큰"을 그대로 기록하는 버그가 있었다면
        // 직전 칸이 계속 first 로 재기록되어 여기서도 통과했을 것이다(재사용의 무한 갱신).
        // 고친 뒤에는 first 가 어느 칸에도 없으므로 거부되고 세션이 폐기된다.
        val e = assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand(first.refreshToken))
        }
        assertEquals(UserErrorCode.REFRESH_TOKEN_INVALID, e.errorCode)
        assertNull(sessions.currentAccessTokenId(userId))
    }

    @Test
    fun `회전된 옛 리프레시 토큰을 재사용하면 InvalidRefreshTokenException 이 발생하고 세션 전체가 폐기된다`() {
        val userId = 2L
        val first = loginSession(userId)
        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))
        // 유예는 바로 직전 한 세대에만 적용된다. first 가 유예 밖(두 세대 전)이 되도록
        // 한 번 더 회전시켜야, 이 테스트가 순수한 재사용 탐지(유예 대상이 아닌 경우)를 검증한다.
        val rotatedAgain = service.refresh(RefreshTokenCommand(rotated.refreshToken))

        val e = assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand(first.refreshToken))
        }
        assertEquals(UserErrorCode.REFRESH_TOKEN_INVALID, e.errorCode)

        // 재사용 탐지로 세션 전체가 폐기됐으므로, 아직 회수하지 않은 최신 토큰마저 통하지 않는다.
        assertFalse(sessions.matchesRefreshToken(userId, rotatedAgain.refreshToken))
        assertNull(sessions.currentAccessTokenId(userId))
    }

    @Test
    fun `재사용 탐지 시 그 시점까지 살아 있던 액세스 토큰이 clear 이전에 블랙리스트에 등록된다`() {
        val userId = 8L
        val first = loginSession(userId)
        val rotated = service.refresh(RefreshTokenCommand(first.refreshToken))
        // 유예는 바로 직전 한 세대에만 적용된다. first 가 두 세대 전이 되도록 한 번 더 회전시켜야
        // 재사용 탐지 경로(clear 이전 블랙리스트 등록)를 탄다.
        val rotatedAgain = service.refresh(RefreshTokenCommand(rotated.refreshToken))
        val rotatedAgainAccessTokenId = sessions.currentAccessTokenId(userId)!!

        assertFalse(sessions.isAccessTokenBlacklisted(rotatedAgainAccessTokenId))

        assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand(first.refreshToken))
        }

        // clear() 가 세션을 지우기 전에 그 시점의 액세스 토큰을 블랙리스트에 넣어야 한다 —
        // 순서가 뒤바뀌면 currentAccessTokenId 가 이미 null 이라 블랙리스트에 넣을 방법이 없어진다.
        assertTrue(sessions.isAccessTokenBlacklisted(rotatedAgainAccessTokenId))
    }

    @Test
    fun `서명 검증에 실패하는 리프레시 토큰은 거부된다`() {
        val e = assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand("forged-or-unknown-refresh-token"))
        }
        assertEquals(UserErrorCode.REFRESH_TOKEN_INVALID, e.errorCode)
    }

    @Test
    fun `액세스 토큰을 리프레시 자리에 넣으면 거부된다`() {
        val userId = 3L
        val issued = loginSession(userId)

        val e = assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand(issued.accessToken))
        }
        assertEquals(UserErrorCode.REFRESH_TOKEN_INVALID, e.errorCode)
    }

    @Test
    fun `저장소의 역할이 ADMIN 이면 새 토큰이 ADMIN 으로 발급된다`() {
        val userId = 11L
        val first = loginSession(userId, role = UserRole.ADMIN)

        service.refresh(RefreshTokenCommand(first.refreshToken))

        assertEquals(UserRole.ADMIN, tokenIssuer.lastRole)
    }

    @Test
    fun `리프레시 토큰은 유효하지만 사용자가 존재하지 않으면 REFRESH_TOKEN_INVALID 가 발생한다`() {
        val userId = 12L
        // loginSession 을 거치지 않고 세션만 직접 만들어, users 저장소에는 없는 사용자를 재현한다(탈퇴 등).
        val tokens = tokenIssuer.issue(userId, UserRole.USER)
        sessions.start(userId, AuthSession(tokens.accessTokenId, tokens.refreshToken, tokens.refreshTokenExpiresAt))

        val e = assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(RefreshTokenCommand(tokens.refreshToken))
        }
        assertEquals(UserErrorCode.REFRESH_TOKEN_INVALID, e.errorCode)
    }
}
