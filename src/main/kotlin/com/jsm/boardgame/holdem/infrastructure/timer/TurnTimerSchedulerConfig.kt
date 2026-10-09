package com.jsm.boardgame.holdem.infrastructure.timer

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler

/**
 * 홀덤 타이머 전용 스케줄러. 가상 스레드를 쓴다 - 타이머 작업은 모두 `tableExecutor.call` 로 그
 * 테이블의 직렬 작업이 끝나길 블로킹으로 기다리므로, 고정 크기 풀이면 느린 테이블(DB 락 대기 등)
 * 몇 개가 스케줄러 스레드를 다 잡아 다른 테이블의 타이머까지 밀린다. 발화마다 자기 가상 스레드를
 * 받으면 블로킹이 테이블 사이에 번지지 않는다.
 * `spring.threads.virtual.enabled` 는 Tomcat 등 다른 실행기까지 바꾸므로 쓰지 않는다.
 */
@Configuration
class TurnTimerSchedulerConfig {

    @Bean
    fun taskScheduler(): TaskScheduler = SimpleAsyncTaskScheduler().apply {
        setVirtualThreads(true)
        setThreadNamePrefix("turn-timer-")
    }
}
