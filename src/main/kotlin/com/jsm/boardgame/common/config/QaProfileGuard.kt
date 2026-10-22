package com.jsm.boardgame.common.config

import org.slf4j.LoggerFactory
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URI

/**
 * qa 프로필(덱 고정 API)이 운영 환경에서 켜지는 것을 기동 단계에서 막는다. 위반이 하나라도 있으면 생성자가 던져
 * 컨텍스트가 뜨지 않는다 — 웹 서버 커넥터는 모든 싱글톤 생성 뒤에 열리므로 덱 API 는 요청을 받기 전에 막힌다.
 *
 * 판정(전부 fail-closed):
 * - 운영 표시 프로필([PROD_PROFILES])이 함께 활성
 * - 실제 연결 DB 호스트가 로컬이 아님 — 프로퍼티가 아니라 Compose 연동·Testcontainers 가 주입한 [JdbcConnectionDetails] 에서 읽는다.
 *   호스트를 못 읽거나(다중 호스트 URL 등) 판단 못 하면 로컬이 아닌 것으로 본다
 * - 환경 변수 `APP_ENV` 가 설정돼 있고 `local` 이 아님
 *
 * 한계: 위 신호 중 어느 것도 운영 표시가 없고 DB 가 로컬 포트포워딩(SSH 터널 등)으로 운영을 가리키면 못 막는다.
 */
@Component
@Profile("qa")
class QaProfileGuard(env: Environment, jdbc: JdbcConnectionDetails) {

    init {
        val violations = violations(env.activeProfiles.toList(), jdbc.jdbcUrl, env.getProperty("APP_ENV"))
        check(violations.isEmpty()) { "qa 프로필은 로컬 개발 환경에서만 켤 수 있다: ${violations.joinToString("; ")}" }
        log.warn("QA 프로필 — 덱 고정 API 활성. 운영에서 절대 켜지 말 것")
    }

    companion object {
        private val log = LoggerFactory.getLogger(QaProfileGuard::class.java)
        private val PROD_PROFILES = setOf("prod", "production", "prd", "live")
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

        fun violations(activeProfiles: List<String>, jdbcUrl: String, appEnv: String?): List<String> = buildList {
            activeProfiles.filter { it.lowercase() in PROD_PROFILES }
                .takeIf { it.isNotEmpty() }?.let { add("운영 프로필 활성: $it") }
            val host = runCatching { URI(jdbcUrl.removePrefix("jdbc:")).host }.getOrNull()
            if (host == null || host.lowercase() !in LOCAL_HOSTS) add("DB 호스트가 로컬이 아니다: ${host ?: "알 수 없음"}")
            if (appEnv != null && !appEnv.trim().equals("local", ignoreCase = true)) add("APP_ENV=$appEnv")
        }
    }
}
