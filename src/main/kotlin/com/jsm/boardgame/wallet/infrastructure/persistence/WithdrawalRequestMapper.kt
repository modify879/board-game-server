package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestStatus

fun WithdrawalRequestJpaEntity.toDomain(): WithdrawalRequest =
    WithdrawalRequest.reconstitute(
        id = WithdrawalRequestId(id),
        userId = userId,
        amount = Money.reconstitute(amount),
        bankAccount = BankAccount.reconstitute(bankName, accountNumber, accountHolder),
        requestedAt = requestedAt,
        status = WithdrawalRequestStatus.valueOf(status),
        processedBy = processedBy,
        processedAt = processedAt,
        rejectionReason = rejectionReason,
        version = version,
    )

fun WithdrawalRequest.toJpaEntity(): WithdrawalRequestJpaEntity =
    WithdrawalRequestJpaEntity(
        id = id?.value ?: 0,
        userId = userId,
        amount = amount.amount,
        bankName = bankAccount.bankName,
        accountNumber = bankAccount.accountNumber,
        accountHolder = bankAccount.accountHolder,
        requestedAt = requestedAt,
        status = status.name,
        processedBy = processedBy,
        processedAt = processedAt,
        rejectionReason = rejectionReason,
        version = version,
    )
