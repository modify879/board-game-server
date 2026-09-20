package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.model.DepositRequestStatus
import com.jsm.boardgame.wallet.domain.model.Money
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@Entity
@Table(
    name = "deposit_requests",
    indexes = [
        Index(name = "idx_deposit_requests_user", columnList = "user_id, id"),
        Index(name = "idx_deposit_requests_status", columnList = "status, id"),
    ],
)
class DepositRequestJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "requested_amount", nullable = false)
    var requestedAmount: Long,

    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant,

    @Column(name = "status", columnDefinition = "text", nullable = false)
    var status: String,

    @Column(name = "credited_amount")
    var creditedAmount: Long?,

    @Column(name = "processed_by")
    var processedBy: Long?,

    @Column(name = "processed_at")
    var processedAt: Instant?,

    @Column(name = "rejection_reason", columnDefinition = "text")
    var rejectionReason: String?,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

interface DepositRequestJpaRepository :
    JpaRepository<DepositRequestJpaEntity, Long>,
    KotlinJdslJpqlExecutor

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
