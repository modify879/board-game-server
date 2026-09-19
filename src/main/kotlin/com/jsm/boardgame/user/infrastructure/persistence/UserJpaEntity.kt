package com.jsm.boardgame.user.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "users",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_users_username", columnNames = ["username"]),
        UniqueConstraint(name = "uk_users_nickname", columnNames = ["nickname"]),
    ],
)
class UserJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "username", columnDefinition = "text", nullable = false)
    var username: String,

    @Column(name = "password_hash", columnDefinition = "text", nullable = false)
    var passwordHash: String,

    @Column(name = "nickname", columnDefinition = "text", nullable = false)
    var nickname: String,

    @Column(name = "profile_image_key", columnDefinition = "text")
    var profileImageKey: String?,

    // ddl-auto: update 로 기존 행이 있는 개발 DB 에 NOT NULL 컬럼을 추가할 때 실패하지 않도록 default 를 둔다.
    @Column(name = "role", columnDefinition = "text default 'USER'", nullable = false)
    var role: String,
)
