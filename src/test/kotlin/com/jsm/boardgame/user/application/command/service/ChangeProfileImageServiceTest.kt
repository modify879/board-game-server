package com.jsm.boardgame.user.application.command.service

import com.jsm.boardgame.user.application.command.usecase.ChangeProfileImageCommand
import com.jsm.boardgame.user.application.port.ProfileImageStorage
import com.jsm.boardgame.user.application.exception.ProfileImageTooLargeException
import com.jsm.boardgame.user.domain.exception.UnsupportedProfileImageTypeException
import com.jsm.boardgame.user.domain.exception.UserErrorCode
import com.jsm.boardgame.user.domain.exception.UserNotFoundException
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.ProfileImageKey
import com.jsm.boardgame.user.domain.model.ProfileImageType
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.transaction.support.TransactionOperations

private class ProfileImageFakeUserRepository : UserRepository {
    val stored = mutableMapOf<Long, User>()
    var failOnSave = false
    var saveThenFail = false // 커밋은 됐는데 호출자는 예외를 받는 모호한 실패
    var findsBeforeFailing = Int.MAX_VALUE

    fun put(userId: Long, imageKey: String?) {
        stored[userId] = User.reconstitute(
            id = UserId(userId),
            username = Username.reconstitute("user_$userId"),
            passwordHash = PasswordHash("hashed"),
            nickname = Nickname.reconstitute("닉네임$userId"),
            profileImageKey = imageKey?.let { ProfileImageKey.reconstitute(it) },
            role = UserRole.USER,
        )
    }

    override fun findByUsername(username: Username): User? = stored.values.find { it.username == username }
    override fun findById(id: UserId): User? {
        if (findsBeforeFailing-- <= 0) throw IllegalStateException("db down: find")
        return stored[id.value]
    }
    // 실제 어댑터처럼 새 인스턴스 — save 전의 메모리 변경이 저장소에 새지 않는다.
    override fun findByIdForUpdate(id: UserId): User? = stored[id.value]?.let {
        User.reconstitute(it.id!!, it.username, it.passwordHash, it.nickname, it.profileImageKey, it.role)
    }
    override fun existsByUsername(username: Username): Boolean = false
    override fun existsByNickname(nickname: Nickname): Boolean = false
    override fun save(user: User): User {
        if (failOnSave) throw IllegalStateException("db down")
        stored[user.id!!.value] = user
        if (saveThenFail) throw IllegalStateException("connection dropped after commit")
        return user
    }
}

private class ChangeProfileImageFakeProfileImageStorage : ProfileImageStorage {
    val files = mutableSetOf<String>()
    var failOnDelete = false
    private var counter = 0

    override fun store(userId: UserId, bytes: ByteArray, type: ProfileImageType): ProfileImageKey {
        val key = ProfileImageKey.of("${userId.value}/${++counter}.${type.extension}")
        files += key.value
        return key
    }

    override fun delete(key: ProfileImageKey) {
        if (failOnDelete) throw java.io.IOException("disk")
        files -= key.value
    }
}

class ChangeProfileImageServiceTest {

    private val users = ProfileImageFakeUserRepository()
    private val storage = ChangeProfileImageFakeProfileImageStorage()
    private val change = ChangeProfileImageService(users, storage, TransactionOperations.withoutTransaction())
    private val remove = RemoveProfileImageService(users, storage, TransactionOperations.withoutTransaction())

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)

    @Test
    fun `이미지를 바꾸면 새 키가 저장되고 옛 파일은 지워진다`() {
        users.put(1L, "1/old.png")
        storage.files += "1/old.png"

        val key = change.change(ChangeProfileImageCommand(1L, png))

        assertEquals(ProfileImageKey.reconstitute(key), users.stored[1L]!!.profileImageKey)
        assertTrue(key.endsWith(".png"))
        assertEquals(setOf(key), storage.files)
    }

    @Test
    fun `DB 저장이 실패하면 새로 쓴 파일을 지우고 예외를 다시 던지며 옛 파일은 둔다`() {
        users.put(1L, "1/old.png")
        storage.files += "1/old.png"
        users.failOnSave = true

        assertFailsWith<IllegalStateException> { change.change(ChangeProfileImageCommand(1L, png)) }

        assertEquals(setOf("1/old.png"), storage.files)
    }

    @Test
    fun `커밋 뒤 연결이 끊긴 모호한 실패면 DB 가 가리키는 새 파일을 지우지 않는다`() {
        users.put(1L, "1/old.png")
        users.saveThenFail = true

        assertFailsWith<IllegalStateException> { change.change(ChangeProfileImageCommand(1L, png)) }

        val newKey = users.stored[1L]!!.profileImageKey!!.value
        assertTrue(newKey in storage.files)
    }

    @Test
    fun `실패 뒤 사용자 재조회마저 실패하면 고아 파일이 낫다 - 새 파일을 지우지 않는다`() {
        users.put(1L, "1/old.png")
        users.failOnSave = true
        users.findsBeforeFailing = 1 // 사전 확인 한 번만 통과, 정리 단계의 재조회부터 실패

        assertFailsWith<IllegalStateException> { change.change(ChangeProfileImageCommand(1L, png)) }

        assertEquals(setOf("1/1.png"), storage.files)
    }

    @Test
    fun `옛 파일 삭제가 실패해도 변경은 성공한다`() {
        users.put(1L, "1/old.png")
        storage.failOnDelete = true

        val key = change.change(ChangeProfileImageCommand(1L, png))

        assertEquals(ProfileImageKey.reconstitute(key), users.stored[1L]!!.profileImageKey)
    }

    @Test
    fun `알 수 없는 형식이면 UNSUPPORTED_TYPE 이고 아무것도 저장하지 않는다`() {
        users.put(1L, null)

        val e = assertFailsWith<UnsupportedProfileImageTypeException> {
            change.change(ChangeProfileImageCommand(1L, "text".toByteArray()))
        }

        assertEquals(UserErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE, e.errorCode)
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun `2MB 를 넘으면 TOO_LARGE 다`() {
        users.put(1L, null)

        val e = assertFailsWith<ProfileImageTooLargeException> {
            change.change(ChangeProfileImageCommand(1L, png + ByteArray(2 * 1024 * 1024)))
        }

        assertEquals(UserErrorCode.PROFILE_IMAGE_TOO_LARGE, e.errorCode)
    }

    @Test
    fun `없는 사용자면 USER_NOT_FOUND 이고 파일을 쓰지 않는다`() {
        val e = assertFailsWith<UserNotFoundException> { change.change(ChangeProfileImageCommand(9L, png)) }

        assertEquals(UserErrorCode.USER_NOT_FOUND, e.errorCode)
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun `제거하면 키가 null 이 되고 파일이 지워진다`() {
        users.put(1L, "1/old.png")
        storage.files += "1/old.png"

        remove.remove(1L)

        assertNull(users.stored[1L]!!.profileImageKey)
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun `이미지가 없는 사용자를 제거해도 조용히 성공한다`() {
        users.put(1L, null)

        remove.remove(1L)

        assertNull(users.stored[1L]!!.profileImageKey)
    }

    @Test
    fun `제거 대상 사용자가 없으면 USER_NOT_FOUND 다`() {
        assertFailsWith<UserNotFoundException> { remove.remove(9L) }
    }
}
