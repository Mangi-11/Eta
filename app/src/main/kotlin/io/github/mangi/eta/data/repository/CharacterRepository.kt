package io.github.mangi.eta.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AtomicFile
import io.github.mangi.eta.agent.roleplay.CharacterCard
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.CharacterCardException
import io.github.mangi.eta.agent.roleplay.CharacterCardPng
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.UserPersona
import io.github.mangi.eta.data.db.CharacterEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.UserPersonaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object CharacterRepository {
    const val MAX_FILE_BYTES = CharacterCardPng.MAX_FILE_BYTES
    @Volatile private var context: Context? = null

    /** 仅绑定上下文；普通工作台启动不读取角色库。 */
    fun initialize(context: Context) { this.context = context.applicationContext }

    private fun appContext() = checkNotNull(context) { "CharacterRepository is not initialized" }
    private fun dao() = EtaDatabase.get(appContext()).characterDao()

    suspend fun list(includeArchived: Boolean = false): List<CharacterProfile> = withContext(Dispatchers.IO) {
        dao().characters(includeArchived).map { it.toProfile() }
    }

    suspend fun get(id: String): CharacterProfile? = withContext(Dispatchers.IO) { dao().character(id)?.toProfile() }

    suspend fun create(card: CharacterCard, avatarBytes: ByteArray? = null): CharacterProfile = withContext(Dispatchers.IO) {
        val validated = validated(card)
        createStored(validated, avatarBytes?.let(::normalizeAvatar))
    }

    private suspend fun createStored(card: CharacterCard, avatarBytes: ByteArray?): CharacterProfile {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val avatar = avatarBytes?.let { writeAvatar(id, it) }
        val profile = CharacterProfile(id, card, avatar, createdAt = now, updatedAt = now)
        try {
            dao().upsertCharacter(profile.toEntity())
        } catch (failure: Throwable) {
            discardNewAvatar(avatar, failure)
            throw failure
        }
        return profile
    }

    suspend fun save(profile: CharacterProfile, avatarBytes: ByteArray? = null): CharacterProfile = withContext(Dispatchers.IO) {
        val existing = dao().character(profile.id) ?: throw IllegalArgumentException("角色已不存在")
        val card = validated(profile.card)
        val avatar = avatarBytes?.let { writeAvatar(profile.id, normalizeAvatar(it), uniqueName = true) }
        val saved = profile.copy(card = card, avatarPath = avatar ?: profile.avatarPath,
            createdAt = existing.createdAt, updatedAt = System.currentTimeMillis())
        try {
            dao().upsertCharacter(saved.toEntity())
        } catch (failure: Throwable) {
            discardNewAvatar(avatar, failure)
            throw failure
        }
        saved
    }

    suspend fun setAvatar(id: String, bytes: ByteArray): CharacterProfile = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException("角色已不存在")
        save(profile, avatarBytes = bytes)
    }

    suspend fun duplicate(id: String): CharacterProfile = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException("角色已不存在")
        createStored(validated(profile.card.withEdits(name = "${profile.card.name} 副本")), avatarBytes(profile.avatarPath))
    }

    suspend fun archive(id: String, archived: Boolean = true) {
        val profile = get(id) ?: throw IllegalArgumentException("角色已不存在")
        save(profile.copy(archived = archived))
    }

    suspend fun import(input: InputStream): CharacterProfile = withContext(Dispatchers.IO) {
        val bytes = input.readRoleplayBytes()
        val png = CharacterCardPng.isPng(bytes)
        try {
            if (png) {
                val card = CharacterCardPng.read(bytes)
                // 保留原始 PNG 的非角色数据块，后续导出只替换角色定义块。
                createStored(validated(card), bytes)
            } else {
                create(CharacterCardCodec.decodeBytes(bytes))
            }
        } catch (failure: CharacterCardException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw CharacterCardException(if (png) "CARD_INVALID_PNG" else "CARD_INVALID_DATA", "角色卡数据无效", failure)
        }
    }

    suspend fun export(id: String, format: CharacterCardFormat, output: OutputStream) = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException("角色已不存在")
        val bytes = when (format) {
            CharacterCardFormat.JSON -> CharacterCardCodec.encodeJson(profile.card).toByteArray(Charsets.UTF_8)
            CharacterCardFormat.PNG -> CharacterCardPng.write(avatarBytes(profile.avatarPath) ?: defaultAvatar(profile.card.name), profile.card)
        }
        output.write(bytes)
        output.flush()
    }

    suspend fun persona(): UserPersona = withContext(Dispatchers.IO) {
        dao().persona()?.let { UserPersona(it.name, it.description) } ?: UserPersona()
    }

    suspend fun savePersona(persona: UserPersona) = withContext(Dispatchers.IO) {
        require(persona.name.isNotBlank() && persona.name.length <= 256) { "用户名称需为 1 至 256 个字符" }
        require(persona.description.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "用户设定超过 1 MiB 限制" }
        dao().upsertPersona(UserPersonaEntity(name = persona.name.trim(), description = persona.description))
    }

    suspend fun binding(id: String): RoleplayBinding {
        val profile = get(id) ?: throw IllegalArgumentException("角色已不存在")
        val persona = persona()
        return RoleplayBinding(profile.id, CharacterCardCodec.encodeJson(profile.card), profile.card.name,
            profile.avatarPath, persona.name, persona.description)
    }

    internal fun avatarBytes(path: String?): ByteArray? {
        if (path == null) return null
        val file = File(path)
        val root = File(appContext().filesDir, "roleplay").canonicalFile
        require(file.canonicalPath.startsWith(root.path + File.separator)) { "角色头像路径无效" }
        return if (file.isFile) file.inputStream().use { it.readRoleplayBytes() } else null
    }

    internal fun writeAvatar(id: String, bytes: ByteArray, uniqueName: Boolean = false): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "角色 ID 无效" }
        require(CharacterCardPng.isPng(bytes)) { "角色头像必须是 PNG" }
        val directory = File(appContext().filesDir, "roleplay/$id")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建角色目录" }
        val file = File(directory, if (uniqueName) "avatar-${UUID.randomUUID()}.png" else "avatar.png")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
        return file.absolutePath
    }

    private fun normalizeAvatar(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_FILE_BYTES) { "头像超过 32 MiB 限制" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) {
            "无法读取头像或图片尺寸过大"
        }
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("无法读取头像图片")
        return try {
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun defaultAvatar(name: String): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(66, 74, 80))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 192f; textAlign = Paint.Align.CENTER }
        canvas.drawText(name.take(1), 256f, 256f - (paint.ascent() + paint.descent()) / 2, paint)
        return try {
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun validated(card: CharacterCard): CharacterCard {
        require(card.name.isNotBlank() && card.name.length <= 512) { "角色名称需为 1 至 512 个字符" }
        return CharacterCardCodec.decodeJson(CharacterCardCodec.encodeJson(card.withEdits(name = card.name.trim())))
    }

    private fun discardNewAvatar(path: String?, failure: Throwable) {
        if (path != null && File(path).exists() && !File(path).delete()) {
            failure.addSuppressed(java.io.IOException("Unable to remove staged character avatar"))
        }
    }

    private fun CharacterEntity.toProfile() = CharacterProfile(id, CharacterCardCodec.decodeJson(cardJson), avatarPath, archived, createdAt, updatedAt)
    private fun CharacterProfile.toEntity() = CharacterEntity(id, card.name, CharacterCardCodec.encodeJson(card), avatarPath, archived, createdAt, updatedAt)
}

private fun InputStream.readRoleplayBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size().toLong() + count > CharacterRepository.MAX_FILE_BYTES) {
            throw CharacterCardException("CARD_TOO_LARGE", "角色卡文件超过 32 MiB 限制")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
