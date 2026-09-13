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
)
