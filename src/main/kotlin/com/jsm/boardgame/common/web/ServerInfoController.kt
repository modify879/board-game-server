package com.jsm.boardgame.common.web

import com.jsm.boardgame.common.config.QaProfileGuard
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class ServerInfoResponse(val environment: String)

/** 인증 없이 서버 환경을 알린다. `qa` 는 표식 검사를 통과해 [QaProfileGuard] 가 떠 있을 때만 나온다 — 프로필 문자열을 따로 보지 않는다. */
@RestController
class ServerInfoController(private val qaGuard: ObjectProvider<QaProfileGuard>) {

    @GetMapping("/api/server-info")
    fun info() = ServerInfoResponse(if (qaGuard.getIfAvailable() != null) "qa" else "default")
}
