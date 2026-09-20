package com.jsm.boardgame.user.infrastructure.security.adapter

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

    // PasswordEncoder#encode 는 널러블 파라미터를 받는 Java API 라 반환 타입도 String? 이지만,
    // "입력이 null 이 아니면 결과도 null 이 아니다"가 계약이고 raw.value 는 항상 non-null 이다.
    override fun hash(raw: RawPassword): PasswordHash = PasswordHash(encoder.encode(raw.value)!!)

    override fun matches(raw: RawPassword, hash: PasswordHash): Boolean =
        encoder.matches(raw.value, hash.value)
}
