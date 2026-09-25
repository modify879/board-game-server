package com.jsm.boardgame.common.web

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import java.time.Duration

/**
 * 열린 STOMP 세션의 토큰을 30초마다 재검증한다. [StompAuthenticationInterceptor] 는 CONNECT
 * 시점에만 토큰을 보므로, 이후 로그아웃·역할변경 블랙리스트·리프레시 재사용 탐지·액세스 토큰
 * 만료(30분)가 일어나도 이미 열린 소켓은 계속 살아 있었다 - 이 컴포넌트가 그 간격을 메운다.
 *
 * 검증은 리소스 서버·CONNECT 인터셉터와 같은 [JwtDecoder] 빈을 그대로 쓴다. 블랙리스트 검증기가
 * 그 디코더에 물려 있어(user/infrastructure/security/config/JwtDecoderConfig), 검증 경로를
 * 새로 만들면 두 경로가 어긋날 수 있다.
 *
 * 스케줄링은 holdem 이 정의한 "taskScheduler" 빈(TurnTimerSchedulerConfig)이나 STOMP 브로커의
 * "messageBrokerTaskScheduler" 빈에 얹지 않는다. common 이 holdem 이 정의한 빈에(설령 이름 매칭을
 * 통한 암묵적 해석이라도) 기대면 안 되고, 브로커 하트비트용 스케줄러에 30초 폴링을 얹는 것도
 * 무관한 관심사를 섞는다. 그래서 [StompSessionSweepSchedulerConfig] 로 전용 TaskScheduler 를
 * 하나 더 만들어 직접 주입받고, `@Scheduled`/`@EnableScheduling` 의 빈 자동 탐색 대신
 * scheduleWithFixedDelay() 로 명시적으로 예약한다 - 컨텍스트에 TaskScheduler 타입 빈이 이미
 * 여러 개 있어(holdem 것 + 브로커 것) `@Scheduled` 가 어떤 걸 고를지 코드만 보고 확신할 수 없다.
 */
@Component
class StompSessionRevalidator(
    private val stompSessionRegistry: StompSessionRegistry,
    private val jwtDecoder: JwtDecoder,
    private val stompSessionSweepScheduler: TaskScheduler,
) {

    @PostConstruct
    fun schedule() {
        stompSessionSweepScheduler.scheduleWithFixedDelay(::sweep, SWEEP_INTERVAL)
    }

    /** 테스트가 30초를 기다리지 않고 직접 호출할 수 있도록 public 으로 둔다. */
    fun sweep() {
        for ((sessionId, token) in stompSessionRegistry.tokenSnapshot()) {
            try {
                jwtDecoder.decode(token)
            } catch (ex: JwtException) {
                val session = stompSessionRegistry.sessionFor(sessionId)
                stompSessionRegistry.remove(sessionId)
                session?.close(CloseStatus.POLICY_VIOLATION.withReason(AUTHENTICATION_REQUIRED_REASON))
                log.info("stomp session revalidation failed, closing session, sessionId={}", sessionId)
            }
        }
    }

    companion object {
        private const val AUTHENTICATION_REQUIRED_REASON = "AUTHENTICATION_REQUIRED"
        private val SWEEP_INTERVAL: Duration = Duration.ofSeconds(30)
        private val log = LoggerFactory.getLogger(StompSessionRevalidator::class.java)
    }
}
