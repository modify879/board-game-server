package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId

fun WalletJpaEntity.toDomain(): Wallet =
    Wallet.reconstitute(
        id = WalletId(id),
        userId = userId,
        balance = Money.reconstitute(balance),
        version = version,
    )

fun Wallet.toJpaEntity(): WalletJpaEntity =
    WalletJpaEntity(
        id = id?.value ?: 0,
        userId = userId,
        balance = balance.amount,
        version = version,
    )
