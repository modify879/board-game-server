package com.jsm.boardgame.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * [Clock] 을 빈으로 등록한다. 도메인 개념이 아니라 기술적 횡단 관심사라 `common` 에 둔다.
 *
 * 코드가 `Instant.now()` 를 직접 호출하면 테스트가 시간을 제어할 방법이 없다 — 유예 창
 * 만료, TTL 경계 같은 시간 의존 로직을 검증하려면 실제로 시간이 흐르길 `Thread.sleep` 으로
 * 기다리는 수밖에 없고, 이는 느리고 드물게 흔들리는 테스트로 이어진다. 그래서 시계도
 * 해싱·셔플·주사위와 같은 무작위성 포트로 취급해 주입받는다 — 운영에서는 이 빈이
 * [Clock.systemUTC] 를 주지만, 테스트는 조정 가능한 시계로 바꿔치기해 시간을 직접 다룬다.
 */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
