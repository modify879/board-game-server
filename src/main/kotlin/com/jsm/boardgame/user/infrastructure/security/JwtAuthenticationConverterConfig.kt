package com.jsm.boardgame.user.infrastructure.security

import com.jsm.boardgame.user.domain.model.UserRole
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter

@Configuration
class JwtAuthenticationConverterConfig {

    @Bean
    fun jwtAuthenticationConverter(): Converter<Jwt, out AbstractAuthenticationToken> =
        JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter { jwt -> listOf(authorityOf(jwt)) }
        }

    // 클레임이 없거나 알 수 없는 값이면 ROLE_USER 로 떨어뜨린다 — 역할 추가 전에 발급된 토큰이
    // 만료 전까지 살아 있어 role 클레임이 없을 수 있다.
    private fun authorityOf(jwt: Jwt): GrantedAuthority {
        val role = UserRole.entries.find { it.name == jwt.getClaimAsString("role") } ?: UserRole.USER
        return SimpleGrantedAuthority("ROLE_${role.name}")
    }
}
