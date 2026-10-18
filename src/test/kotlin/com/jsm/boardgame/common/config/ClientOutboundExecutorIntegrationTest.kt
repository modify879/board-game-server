package com.jsm.boardgame.common.config

import com.jsm.boardgame.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ClientOutboundExecutorIntegrationTest {

    @Autowired
    @Qualifier("clientOutboundChannelExecutor")
    lateinit var executor: Executor

    @Test
    fun `clientOutboundChannel 실행기는 전송마다 가상 스레드에서 돈다`() {
        val virtual = CompletableFuture<Boolean>()

        executor.execute { virtual.complete(Thread.currentThread().isVirtual) }

        assertThat(virtual.get(2, TimeUnit.SECONDS)).isTrue()
    }
}
