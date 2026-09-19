package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.model.DepositRequestStatus
import com.jsm.boardgame.wallet.domain.model.Money

fun DepositRequestJpaEntity.toDomain(): DepositRequest =
    DepositRequest.reconstitute(
        id = DepositRequestId(id),
        userId = userId,
        requestedAmount = Money.reconstitute(requestedAmount),
        requestedAt = requestedAt,
        status = DepositRequestStatus.valueOf(status),
        creditedAmount = creditedAmount?.let(Money::reconstitute),
        processedBy = processedBy,
        processedAt = processedAt,
        rejectionReason = rejectionReason,
        version = version,
    )

fun DepositRequest.toJpaEntity(): DepositRequestJpaEntity =
    DepositRequestJpaEntity(
        id = id?.value ?: 0,
        userId = userId,
        requestedAmount = requestedAmount.amount,
        requestedAt = requestedAt,
        status = status.name,
        creditedAmount = creditedAmount?.amount,
        processedBy = processedBy,
        processedAt = processedAt,
        rejectionReason = rejectionReason,
        version = version,
    )
