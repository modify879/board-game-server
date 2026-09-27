package com.jsm.boardgame.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * StompSessionRegistry 전용 스케줄러. 세션마다 만료(또는 폐기) 시각에 닫는 예약 작업 하나씩만
 * 여기서 돈다 — 다른 작업과 섞이지 않게 풀을 분리한다.
 * 빈 이름(stompSessionScheduler)이 StompSessionRegistry 생성자 파라미터 이름과 같아, 같은
 * TaskScheduler 타입 빈이 여럿(holdem 의 "taskScheduler", 브로커의 "messageBrokerTaskScheduler")
 * 있어도 스프링이 이름으로 정확히 이 빈을 고른다.
 */
@Configuration
class StompSessionSchedulerConfig {

    @Bean
    fun stompSessionScheduler(): TaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("stomp-session-")
    }
}
