package com.jsm.boardgame.holdem.presentation.rest

import com.jsm.boardgame.common.error.AuthenticationRequiredException
import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestCommand
import com.jsm.boardgame.holdem.application.command.usecase.CancelJoinRequestUseCase
import com.jsm.boardgame.holdem.application.command.usecase.CreateTableUseCase
import com.jsm.boardgame.holdem.application.command.usecase.PlayActionUseCase
import com.jsm.boardgame.holdem.application.command.usecase.SitDownOutcome
import com.jsm.boardgame.holdem.application.command.usecase.SitDownUseCase
import com.jsm.boardgame.holdem.application.query.service.HoldemTableQueryService
import com.jsm.boardgame.holdem.presentation.rest.request.CreateTableRequest
import com.jsm.boardgame.holdem.presentation.rest.request.PlayActionRequest
import com.jsm.boardgame.holdem.presentation.rest.request.SitDownRequest
import com.jsm.boardgame.holdem.presentation.rest.response.TableCreatedResponse
import com.jsm.boardgame.holdem.presentation.rest.response.TableSummaryResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// 테이블 자원(방 생성/조회/착석/핸드 진행)을 다룬다. 좌석 하나로 유일하게 찾히는 내 좌석 자원(기립, me/seat)은
// HoldemSeatController 가 맡는다. 핸드 시작은 수동 진입점이 없다 — 자동 시작만 있다(HandStarter/StartScheduledHandService).
@RestController
@RequestMapping("/api/holdem/tables")
class HoldemTableController(
    private val createTableUseCase: CreateTableUseCase,
    private val sitDownUseCase: SitDownUseCase,
    private val cancelJoinRequestUseCase: CancelJoinRequestUseCase,
    private val playActionUseCase: PlayActionUseCase,
    private val holdemTableQueryService: HoldemTableQueryService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createTable(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: CreateTableRequest): TableCreatedResponse =
        TableCreatedResponse.from(createTableUseCase.create(request.toCommand(jwt.requireUserId())))

    @GetMapping
    fun listTables(@AuthenticationPrincipal jwt: Jwt, pageable: Pageable): Page<TableSummaryResponse> =
        holdemTableQueryService.findAll(jwt.requireUserId(), pageable).map(TableSummaryResponse::from)

    // 핸드가 없으면 즉시 착석(201), 진행 중이면 참가 요청만 남긴다(202) — 어느 쪽인지는 응답 상태로만 구분된다.
    @PostMapping("/{tableId}/seats")
    fun sitDown(@AuthenticationPrincipal jwt: Jwt, @PathVariable tableId: Long, @RequestBody request: SitDownRequest): ResponseEntity<Void> {
        val outcome = sitDownUseCase.sitDown(request.toCommand(tableId, jwt.requireUserId()))
        val status = if (outcome == SitDownOutcome.SEATED) HttpStatus.CREATED else HttpStatus.ACCEPTED
        return ResponseEntity.status(status).build()
    }

    @DeleteMapping("/{tableId}/seats/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelJoinRequest(@AuthenticationPrincipal jwt: Jwt, @PathVariable tableId: Long) {
        cancelJoinRequestUseCase.cancel(CancelJoinRequestCommand(tableId, jwt.requireUserId()))
    }

    @PostMapping("/{tableId}/hands/actions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun playAction(@AuthenticationPrincipal jwt: Jwt, @PathVariable tableId: Long, @RequestBody request: PlayActionRequest) {
        playActionUseCase.play(request.toCommand(tableId, jwt.requireUserId()))
    }
}

// AuthController.logout(), WalletController 와 같은 방식이다 — subject 가 숫자로 파싱되지 않으면 401(오류 계약)로 변환한다.
private fun Jwt.requireUserId(): Long =
    subject?.toLongOrNull()
        ?: throw AuthenticationRequiredException("인증된 JWT 의 subject 를 사용자 식별자로 파싱할 수 없다: subject=$subject")
