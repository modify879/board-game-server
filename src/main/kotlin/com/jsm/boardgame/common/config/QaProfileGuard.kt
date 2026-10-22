package com.jsm.boardgame.common.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * qa 프로필(덱 고정 API)이 실제 판이 도는 DB 에 붙는 것을 기동 단계에서 막는다. 위반이 하나라도 있으면 생성자가 던져
 * 컨텍스트가 뜨지 않는다 — 웹 서버 커넥터는 모든 싱글톤 생성 뒤에 열리므로 덱 API 는 요청을 받기 전에 막힌다.
 *
 * 방식은 Rails 의 `ar_internal_metadata` 와 같다: DB 자신이 환경을 기록하고, 위험한 기능을 켜기 전에 그 기록을 확인한다.
 * 실제로 연결된 DataSource 에서 `select environment from app_environment` 를 읽어 정확히 한 행이고 값이 `qa` 일 때만 통과한다.
 * 테이블·행이 없거나, 여러 행이거나, 값이 다르거나, 조회가 실패하면 전부 거부(fail-closed)다.
 * 표식은 compose-qa.yaml 이 마운트하는 초기화 SQL 이 QA DB 에만 만든다 — data.sql 에 넣지 않는다(두 DB 모두에서 실행된다).
 * `app_environment` 는 JPA 엔티티가 아니고 `ddl-auto: update` 는 추가만 하고 엔티티에 없는 테이블을 지우지 않으므로 표식이 남는다.
 *
 * 운영 표시 프로필([PROD_PROFILES])이 함께 활성이어도 거부한다.
 *
 * 한계: 누가 실제로 쓰는 DB 에 직접 `app_environment` 표식을 넣으면 뚫린다.
 */
@Component
@Profile("qa")
class QaProfileGuard(env: Environment, dataSource: DataSource) {

    init {
        val environments = runCatching {
            JdbcTemplate(dataSource).queryForList("select environment from app_environment", String::class.java).map { it ?: "null" }
        }
        val violations = violations(env.activeProfiles.toList(), environments)
        check(violations.isEmpty()) { "qa 프로필은 app_environment 표식이 qa 인 DB 에서만 켤 수 있다: ${violations.joinToString("; ")}" }
        log.warn("QA 프로필 — 덱 고정 API 활성. 실제로 쓰는 DB 에 붙이지 말 것")
    }

    companion object {
        private val log = LoggerFactory.getLogger(QaProfileGuard::class.java)
        private val PROD_PROFILES = setOf("prod", "production", "prd", "live")

        /** [environments] 는 `app_environment` 조회 결과이고, 조회 실패(테이블 없음 등)는 failure 로 넘긴다. */
        fun violations(activeProfiles: List<String>, environments: Result<List<String>>): List<String> = buildList {
            activeProfiles.filter { it.lowercase() in PROD_PROFILES }
                .takeIf { it.isNotEmpty() }?.let { add("운영 프로필 활성: $it") }
            environments.fold(
                onSuccess = {
                    when {
                        it.isEmpty() -> add("app_environment 에 행이 없다")
                        it.size > 1 -> add("app_environment 에 행이 ${it.size}개다")
                        it.single() != "qa" -> add("app_environment 값이 qa 가 아니다: ${it.single()}")
                    }
                },
                onFailure = { add("app_environment 를 읽지 못했다: ${it.message}") },
            )
        }
    }
}
