package com.jsm.boardgame.user.infrastructure.storage

import com.jsm.boardgame.user.application.port.ProfileImageStorage
import com.jsm.boardgame.user.domain.model.ProfileImageKey
import com.jsm.boardgame.user.domain.model.ProfileImageType
import com.jsm.boardgame.user.domain.model.UserId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

@Component
class LocalProfileImageStorage(
    @Value("\${app.profile-image.storage-dir}") storageDir: String,
) : ProfileImageStorage {

    private val root: Path = Paths.get(storageDir).toAbsolutePath().normalize()

    override fun store(userId: UserId, bytes: ByteArray, type: ProfileImageType): ProfileImageKey {
        val key = ProfileImageKey.of("${userId.value}/${UUID.randomUUID()}.${type.extension}")
        val target = resolve(key)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        return key
    }

    override fun delete(key: ProfileImageKey) {
        Files.deleteIfExists(resolve(key))
    }

    // 키는 검증된 값이지만 저장소 밖을 가리키면 파일을 쓰거나 지우게 되므로 한 번 더 막는다.
    private fun resolve(key: ProfileImageKey): Path {
        val target = root.resolve(key.value).normalize()
        check(target.startsWith(root)) { "저장소 밖을 가리키는 키: ${key.value}" }
        return target
    }
}
