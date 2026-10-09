package com.jsm.boardgame.common.web

import com.jsm.boardgame.TestcontainersConfiguration
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.TransientDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class, StatementTimeoutTranslationIntegrationTest.Config::class)
class StatementTimeoutTranslationIntegrationTest {

    // 테스트 클래스 안에 둬야 컴포넌트 스캔에서 빠진다(최상위면 모든 테스트 컨텍스트에 등록된다); @Repository 는 예외 번역을 타기 위해 필요하다.
    @Repository
    class StatementTimeoutProbeRepository {
        @PersistenceContext
        lateinit var em: EntityManager

        fun sleep() { em.createNativeQuery("select pg_sleep(2)").singleResult }
    }

    @TestConfiguration
    class Config {
        @Bean
        fun statementTimeoutProbeRepository(): StatementTimeoutProbeRepository = StatementTimeoutProbeRepository()
    }

    @Autowired lateinit var probe: StatementTimeoutProbeRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    @Transactional
    fun `Postgres statement_timeout 은 TransientDataAccessException 으로 번역된다`() {
        jdbc.execute("set local statement_timeout = '100ms'")

        val e = assertFailsWith<Throwable> { probe.sleep() }

        val chain = generateSequence(e) { it.cause }.map { it.javaClass.name + ": " + it.message?.take(80) }.toList()
        assertTrue(e is TransientDataAccessException, "실제 타입: $chain")
    }
}
