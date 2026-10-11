package com.jsm.boardgame.holdem.presentation.rest

import com.jsm.boardgame.common.error.AuthenticationRequiredException
import com.jsm.boardgame.holdem.application.query.service.HandHistoryQueryService
import com.jsm.boardgame.holdem.presentation.rest.response.HandHistoryResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// 하우스 룰 11. 정렬은 종료 시각 내림차순으로 고정이다 — pageable 의 sort 는 쓰지 않는다.
@RestController
class HoldemHandHistoryController(
    private val handHistoryQueryService: HandHistoryQueryService,
) {
    @GetMapping("/api/holdem/me/hands")
    fun myHands(@AuthenticationPrincipal jwt: Jwt, pageable: Pageable): Page<HandHistoryResponse> {
        val userId = jwt.subject?.toLongOrNull()
            ?: throw AuthenticationRequiredException("인증된 JWT 의 subject 를 사용자 식별자로 파싱할 수 없다: subject=${jwt.subject}")
        return handHistoryQueryService.findMine(userId, pageable).map(HandHistoryResponse::from)
    }
}

// 인가는 SecurityConfig 의 admin 경로 규칙(hasRole("ADMIN"))이 처리한다. 마스킹 없이 전체 패를 돌려준다.
@RestController
@RequestMapping("/api/admin/holdem")
class AdminHoldemHandHistoryController(
    private val handHistoryQueryService: HandHistoryQueryService,
) {
    @GetMapping("/hands")
    fun hands(
        @RequestParam(required = false) userId: Long?,
        @RequestParam(required = false) tableId: Long?,
        pageable: Pageable,
    ): Page<HandHistoryResponse> =
        handHistoryQueryService.findAll(userId, tableId, pageable).map(HandHistoryResponse::from)
}
