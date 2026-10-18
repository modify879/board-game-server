package com.jsm.boardgame.holdem.application.config

import com.jsm.boardgame.holdem.domain.model.TablePolicy
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 새 테이블에 적용할 블라인드·바이인 범위. 테이블은 만들어질 때의 값을 [TablePolicy] 로 복사해 들고 있으므로
 * 설정을 바꿔도 열려 있는 테이블에는 영향이 없다. 불변식 위반이면 여기서 예외가 나 기동이 실패한다.
 */
@ConfigurationProperties(prefix = "app.holdem.table-policy")
data class TablePolicyProperties(
    val smallBlind: Long,
    val bigBlind: Long,
    val minBuyInBigBlinds: Int,
    val maxBuyInBigBlinds: Int,
) {
    init {
        toPolicy()
    }

    fun toPolicy(): TablePolicy = TablePolicy.of(smallBlind, bigBlind, minBuyInBigBlinds, maxBuyInBigBlinds)
}
