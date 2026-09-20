package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.LoginCommand
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.AuthTokenIssuer
import com.jsm.boardgame.user.application.port.IssuedTokens
import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.application.exception.LoginFailedException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import com.jsm.boardgame.user.domain.service.PasswordHasher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// 파일 최상위 클래스는 private 이어도 JVM 클래스명은 그대로 노출되어, 같은 패키지의
// 다른 테스트 파일(SignUpServiceTest 등)과 이름이 겹치면 실제로 충돌한다(Redeclaration).
// 그래서 이 파일의 픽스처는 Login 접두어를 붙여 파일 간 이름 충돌을 피한다.
private class LoginFakeUserRepository : UserRepository {
    val stored = mutableListOf<User>()

    override fun findByUsername(username: Username): User? = stored.find { it.username == username }

    override fun findById(id: UserId): User? = stored.find { it.id == id }

    override fun existsByUsername(username: Username): Boolean = stored.any { it.username == username }

    override fun existsByNickname(nickname: Nickname): Boolean = stored.any { it.nickname == nickname }

    override fun save(user: User): User {
        stored += user
        return user
    }

    fun seed(id: Long, username: String, rawPassword: String, nickname: String, role: UserRole = UserRole.USER): User {
        val user = User.reconstitute(
            id = UserId(id),
            username = Username.of(username),
            passwordHash = PasswordHash("hashed:$rawPassword"),
            nickname = Nickname.of(nickname),
            profileImageKey = null,
            role = role,
        )
        stored += user
        return user
    }
}

private class LoginFakePasswordHasher : PasswordHasher {
    override fun hash(raw: RawPassword): PasswordHash = PasswordHash("hashed:${raw.value}")

    override fun matches(raw: RawPassword, hash: PasswordHash): Boolean =
        hash.value == "hashed:${raw.value}"
}

private class LoginFakeAuthTokenIssuer : AuthTokenIssuer {
    private var counter = 0

    override fun issue(userId: Long, role: UserRole): IssuedTokens {
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

private class LoginInMemoryAuthSessionStore : AuthSessionStore {
    private val sessions = mutableMapOf<Long, AuthSession>()
    private val blacklist = mutableMapOf<String, Instant>()
    private val refreshTokenIndex = mutableMapOf<String, Long>()

    override fun start(userId: Long, session: AuthSession) {
        sessions[userId] = session
        refreshTokenIndex[session.refreshToken] = userId
    }

    override fun userIdForRefreshToken(refreshToken: String): Long? = refreshTokenIndex[refreshToken]

    // AuthSessionStore 포트 계약이 아니다 — 세션이 실제로 시작됐는지 들여다보는 페이크 전용 검사용 헬퍼다.
    fun matchesRefreshToken(userId: Long, refreshToken: String): Boolean =
        sessions[userId]?.refreshToken == refreshToken

    override fun currentAccessTokenId(userId: Long): String? = sessions[userId]?.accessTokenId

    override fun clear(userId: Long) {
        sessions.remove(userId)
    }

    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) {
        blacklist[accessTokenId] = expiresAt
    }

    override fun isAccessTokenBlacklisted(accessTokenId: String): Boolean = blacklist.containsKey(accessTokenId)

    // 이 픽스처는 그레이스 개념 없이 정확히 일치할 때만 통과한다 — LoginService 는 rotate() 를
    // 쓰지 않고 항상 start() 로 세션을 여므로, 여기서는 인터페이스 구현을 위한 최소 구현이다.
    override fun rotate(userId: Long, presentedRefreshToken: String, next: AuthSession): RotationResult {
        val session = sessions[userId] ?: return RotationResult.Mismatch
        if (session.refreshToken != presentedRefreshToken) return RotationResult.Mismatch

        val previousAccessTokenId = session.accessTokenId
        start(userId, next)
        return RotationResult.Rotated(previousAccessTokenId)
    }
}

class LoginServiceTest {

    private val users = LoginFakeUserRepository()
    private val passwordHasher = LoginFakePasswordHasher()
    private val tokenIssuer = LoginFakeAuthTokenIssuer()
    private val sessions = LoginInMemoryAuthSessionStore()
    private val service = LoginService(users, passwordHasher, tokenIssuer, sessions, Duration.ofMinutes(30), Clock.systemUTC())

    @Test
    fun `존재하지 않는 사용자명이면 LoginFailedException 이 발생한다`() {
        val e = assertFailsWith<LoginFailedException> {
            service.login(LoginCommand(username = "user_01", password = "password1"))
        }
        assertEquals(UserErrorCode.LOGIN_FAILED, e.errorCode)
    }

    @Test
    fun `비밀번호가 틀리면 LoginFailedException 이 발생한다`() {
        users.seed(id = 1, username = "user_01", rawPassword = "password1", nickname = "길동이")

        val e = assertFailsWith<LoginFailedException> {
            service.login(LoginCommand(username = "user_01", password = "wrong-password"))
        }
        assertEquals(UserErrorCode.LOGIN_FAILED, e.errorCode)
    }

    @Test
    fun `사용자명 형식이 잘못돼도 LoginFailedException 으로만 노출된다`() {
        val e = assertFailsWith<LoginFailedException> {
            service.login(LoginCommand(username = "1", password = "password1"))
        }
        assertEquals(UserErrorCode.LOGIN_FAILED, e.errorCode)
    }

    @Test
    fun `재로그인하면 이전 액세스 토큰이 블랙리스트에 오른다`() {
        users.seed(id = 1, username = "user_01", rawPassword = "password1", nickname = "길동이")

        service.login(LoginCommand(username = "user_01", password = "password1"))
        val firstAccessTokenId = sessions.currentAccessTokenId(1)!!
        assertTrue(!sessions.isAccessTokenBlacklisted(firstAccessTokenId))

        service.login(LoginCommand(username = "user_01", password = "password1"))
        assertTrue(sessions.isAccessTokenBlacklisted(firstAccessTokenId))
    }

    @Test
    fun `정상 로그인하면 토큰이 발급되고 세션이 시작된다`() {
        val userId = 1L
        users.seed(id = userId, username = "user_01", rawPassword = "password1", nickname = "길동이")

        val tokens = service.login(LoginCommand(username = "user_01", password = "password1"))

        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())
        assertTrue(sessions.matchesRefreshToken(userId, tokens.refreshToken))
    }
}
