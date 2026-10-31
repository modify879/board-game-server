package com.jsm.boardgame.wallet.infrastructure.persistence.adapter

import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.wallet.application.exception.AdjustmentAlreadyAppliedException
import com.jsm.boardgame.wallet.application.port.AdjustmentKeyRegistry
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.UserId
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 관리자 조정 요청의 이중 처리 방어를 DB 유일성으로 확인한다. 인메모리 페이크는 PK 이름을 모르므로,
 * wallet_adjustment_keys_pkey 위반이 실제로 AdjustmentAlreadyAppliedException 으로 번역되는지는
 * 이 테스트가 잡는다. 이 테이블에는 FK 가 없어(관리자·대상 사용자 존재는 서비스 단의 UserExistence
 * 체크와 fk_wallets_user 가 이미 보장한다) 실제 users 행을 만들 필요가 없다.
 */
@IntegrationTest
class AdjustmentKeyRegistryAdapterIntegrationTest {

    @Autowired
    private lateinit var registry: AdjustmentKeyRegistry

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `같은 키를 두 트랜잭션에서 두 번 claim 하면 두 번째는 AdjustmentAlreadyAppliedException`() {
        val key = UUID.randomUUID().toString()
        val admin = UserId(syntheticUserId())
        val target = UserId(admin.value + 1)

        registry.claim(key, adminUserId = admin, targetUserId = target, at = now)

        val e = assertFailsWith<AdjustmentAlreadyAppliedException> {
            registry.claim(key, adminUserId = admin, targetUserId = target, at = now)
        }
        assertEquals(WalletErrorCode.ADJUSTMENT_ALREADY_APPLIED, e.errorCode)
    }

    /** 실제 IDENTITY id 와 겹치지 않는 대역(1조~)의 합성 userId. */
    private fun syntheticUserId(): Long = 1_000_000_000_000L + (System.nanoTime() ushr 1)
}
