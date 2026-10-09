package com.jsm.boardgame.common.web

import com.jsm.boardgame.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * `@JvmInline value class` 가 프레임워크 경계(Spring MVC 바인딩, Jackson 3 + kotlin 모듈)에서
 * 실제로 어떻게 동작하는지 박제한다. 운영 코드는 이 경계에서 원시 타입을 유지한다 — 이 테스트는 그 근거다.
 * 프레임워크 동작이 바뀌어 이 테스트가 깨지면 그 결정을 다시 볼 때다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, ValueClassBindingIntegrationTest.SpikeConfig::class)
class ValueClassBindingIntegrationTest {

    @JvmInline
    value class SpikeId(val value: Long)

    data class SpikeBody(val id: SpikeId)

    @RestController
    class SpikeController {
        @GetMapping("/spike/path/{id}")
        fun path(@PathVariable id: SpikeId): String = "path:${id.value}"

        @GetMapping("/spike/param")
        fun param(@RequestParam id: SpikeId): String = "param:${id.value}"

        @PostMapping("/spike/body")
        fun body(@RequestBody body: SpikeBody): String = "body:${body.id.value}"

        @GetMapping("/spike/response")
        fun response(): SpikeBody = SpikeBody(SpikeId(1))
    }

    @TestConfiguration
    class SpikeConfig {
        @Bean
        fun spikeController() = SpikeController()

        // 운영 보안 설정은 건드리지 않고 /spike/** 만 테스트 전용 체인으로 연다.
        @Bean
        @Order(0)
        fun spikeChain(http: HttpSecurity): SecurityFilterChain {
            http {
                securityMatcher("/spike/**")
                csrf { disable() }
                authorizeHttpRequests { authorize(anyRequest, permitAll) }
            }
            return http.build()
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc

    private fun statusAndBody(builder: org.springframework.test.web.servlet.RequestBuilder): Pair<Int, String> {
        val r = mockMvc.perform(builder).andReturn().response
        return r.status to r.contentAsString
    }

    @Test
    fun `PathVariable 의 value class 는 바인딩되어 값이 들어온다`() {
        // 결과: 200, "path:7". 스프링 7 이 Kotlin value class 를 생성자 변환으로 바인딩한다.
        assertThat(statusAndBody(get("/spike/path/7"))).isEqualTo(200 to "path:7")
    }

    @Test
    fun `RequestParam 의 value class 는 바인딩되어 값이 들어온다`() {
        // 결과: 200, "param:7".
        assertThat(statusAndBody(get("/spike/param").param("id", "7"))).isEqualTo(200 to "param:7")
    }

    @Test
    fun `RequestBody 의 value class 필드는 원시 값으로 역직렬화된다`() {
        // 결과: 200, "body:5". Jackson 3(tools.jackson) + jackson-module-kotlin 이 value class 를 원시 값으로 푼다.
        val result = statusAndBody(post("/spike/body").contentType(MediaType.APPLICATION_JSON).content("""{"id":5}"""))
        assertThat(result).isEqualTo(200 to "body:5")
    }

    @Test
    fun `응답 본문의 value class 필드는 원시 값으로 직렬화된다`() {
        // 결과: {"id":1}. 이름 맹글링(id-xxxx)도 {"id":{"value":1}} 래핑도 없다.
        assertThat(statusAndBody(get("/spike/response"))).isEqualTo(200 to """{"id":1}""")
    }

}
