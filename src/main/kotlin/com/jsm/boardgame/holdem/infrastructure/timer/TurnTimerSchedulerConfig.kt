package com.jsm.boardgame.holdem.infrastructure.timer

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/** 차례 타이머 전용 스케줄러. 풀을 작게 유지한다 - 테이블당 최대 하나의 예약만 살아있는다. */
@Configuration
class TurnTimerSchedulerConfig {

    @Bean
    fun taskScheduler(): TaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 2
        setThreadNamePrefix("turn-timer-")
    }
}
