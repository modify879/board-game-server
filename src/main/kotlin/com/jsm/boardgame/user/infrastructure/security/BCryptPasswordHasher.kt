package com.jsm.boardgame.user.infrastructure.security

import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.RawPassword
import com.jsm.boardgame.user.domain.service.PasswordHasher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * 도메인의 `PasswordHasher` 포트 구현체. 스프링 시큐리티의 `PasswordEncoder` 타입이
 * 여기서 멈추고 `infrastructure` 밖(domain·application)으로 새어나가지 않도록 막는 경계다.
 */
@Component
class BCryptPasswordHasher(
    private val encoder: PasswordEncoder,
) : PasswordHasher {

    // 사용자가 존재하지 않을 때 비교 대상으로 쓸 더미 해시. 실제 BCrypt 형식(`$2a$10$...`)이어야
    // 진짜 해시와 연산 비용이 같아 응답 시간이 맞는다. 문자열 리터럴로 박아 넣으면 cost factor 가
    // 실제 encoder(SecurityConfig#passwordEncoder) 설정과 어긋날 수 있으므로, 주입받은 encoder 로
    // 이 빈이 생성되는 시점(스프링 싱글턴이라 앱 기동 시 1회)에 직접 만들어 캐시해 둔다.
    private val dummyHash: PasswordHash = PasswordHash(encoder.encode(DUMMY_RAW_PASSWORD)!!)

    // PasswordEncoder#encode 는 널러블 파라미터를 받는 Java API 라 반환 타입도 String? 이지만,
    // "입력이 null 이 아니면 결과도 null 이 아니다"가 계약이고 raw.value 는 항상 non-null 이다.
    override fun hash(raw: RawPassword): PasswordHash = PasswordHash(encoder.encode(raw.value)!!)

    override fun matches(raw: RawPassword, hash: PasswordHash?): Boolean {
        if (hash == null) {
            // 사용자가 없을 때도 BCrypt 비교를 실제로 수행해, 있을 때(비밀번호 틀림 → BCrypt
            // 비교 ~100ms)와 응답 시간을 맞춘다. 결과는 쓰지 않고 무조건 false 를 반환하지만,
            // "결과를 안 쓰는데 왜 계산하나" 싶어 이 줄을 지우면 시간차가 다시 벌어지고
            // 그 시간차로 계정 존재 여부가 새어나간다 — 지우지 말 것.
            encoder.matches(raw.value, dummyHash.value)
            return false
        }
        return encoder.matches(raw.value, hash.value)
    }

    companion object {
        // 실제 값 자체는 의미가 없다 — encoder.encode() 가 매번 다른 솔트로 진짜 BCrypt 해시를
        // 만들어내기 위한 입력일 뿐이다.
        private const val DUMMY_RAW_PASSWORD = "dummy-password-for-constant-time-login"
    }
}
