package com.jsm.boardgame.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** [Clock] 을 빈으로 등록한다. 도메인 개념이 아니라 기술적 횡단 관심사라 `common` 에 둔다 (규칙 5). */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
