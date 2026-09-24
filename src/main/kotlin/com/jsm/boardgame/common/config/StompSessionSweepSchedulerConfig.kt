package com.jsm.boardgame.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * StompSessionRevalidator 전용 스케줄러. 풀을 하나만 두는 이유는 sweep() 하나만 여기서 돈다는 걸
 * 보장해서다 - 다른 작업과 섞이면 30초 주기가 밀릴 수 있다.
 * 빈 이름(stompSessionSweepScheduler)이 StompSessionRevalidator 생성자 파라미터 이름과 같아,
 * 같은 TaskScheduler 타입 빈이 여럿(holdem 의 "taskScheduler", 브로커의
 * "messageBrokerTaskScheduler") 있어도 스프링이 이름으로 정확히 이 빈을 고른다 - TurnTimer/
 * ConnectionTimer 가 "taskScheduler" 라는 이름으로 자기 빈을 집어가는 것과 같은 방식이다.
 */
@Configuration
class StompSessionSweepSchedulerConfig {

    @Bean
    fun stompSessionSweepScheduler(): TaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("stomp-session-sweep-")
    }
}
