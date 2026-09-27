package com.jsm.boardgame.wallet.infrastructure.persistence.entity

import com.jsm.boardgame.wallet.domain.model.BankAccount
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestStatus
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Entity
@Table(
    name = "withdrawal_requests",
    indexes = [
        Index(name = "idx_withdrawal_requests_user", columnList = "user_id, id"),
        Index(name = "idx_withdrawal_requests_status", columnList = "status, id"),
    ],
)
class WithdrawalRequestJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "amount", nullable = false)
    var amount: Long,

    // BankAccount 는 @Embedded 를 쓰지 않고 컬럼 세 개로 편다 — 도메인/JPA 분리.
    @Column(name = "bank_name", columnDefinition = "text", nullable = false)
    var bankName: String,

    @Column(name = "account_number", columnDefinition = "text", nullable = false)
    var accountNumber: String,

    @Column(name = "account_holder", columnDefinition = "text", nullable = false)
    var accountHolder: String,

    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant,

    @Column(name = "status", columnDefinition = "text", nullable = false)
    var status: String,

    @Column(name = "processed_by")
    var processedBy: Long?,

    @Column(name = "processed_at")
    var processedAt: Instant?,

    @Column(name = "rejection_reason", columnDefinition = "text")
    var rejectionReason: String?,
)

interface WithdrawalRequestJpaRepository :
    JpaRepository<WithdrawalRequestJpaEntity, Long>,
    KotlinJdslJpqlExecutor {

    /**
     * 명령 경로 전용 — 잠금 순서(요청 행 → 지갑 행)의 첫 자리다. `findById` 대신 이 메서드를 쓴다.
     * `@Transactional` 이 필요한 이유는 `WalletJpaRepository.findByUserIdForUpdate` 와 같다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional
    @Query("select w from WithdrawalRequestJpaEntity w where w.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): WithdrawalRequestJpaEntity?
}

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
    )
