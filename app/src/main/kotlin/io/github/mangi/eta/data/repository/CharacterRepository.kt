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
    private const val SEED_PREFS = "eta_roleplay"
    private const val KEY_DEFAULT_CHARACTER_SEEDED = "default_character_seeded"
    @Volatile private var context: Context? = null

    /** 仅绑定上下文；普通工作台启动不读取角色库。 */
    fun initialize(context: Context) { this.context = context.applicationContext }

    private fun appContext() = checkNotNull(context) { "CharacterRepository is not initialized" }
    private fun dao() = EtaDatabase.get(appContext()).characterDao()

    suspend fun list(): List<CharacterProfile> = withContext(Dispatchers.IO) {
        dao().characters().map { it.toProfile() }
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

    suspend fun duplicate(id: String): CharacterProfile = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException("角色已不存在")
        createStored(validated(profile.card.withEdits(name = "${profile.card.name} 副本")), avatarBytes(profile.avatarPath))
    }

    /** 删除角色及其图片、剧情记忆目录；已有对话保留当时的角色快照，不受影响。 */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        get(id) ?: throw IllegalArgumentException("角色已不存在")
        dao().deleteCharacter(id)
        CharacterMemoryRepository.discard(appContext(), id)
    }

    /** 新角色库播种一次默认角色；已有角色的库和删除过默认角色都不再播种。 */
    suspend fun ensureDefaultCharacter() = withContext(Dispatchers.IO) {
        val prefs = appContext().getSharedPreferences(SEED_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DEFAULT_CHARACTER_SEEDED, false)) return@withContext
        if (dao().characters().isEmpty()) {
            createDefaultCharacterStored()
        }
        prefs.edit().putBoolean(KEY_DEFAULT_CHARACTER_SEEDED, true).apply()
    }

    /** 用户主动恢复内置默认角色；与一次性播种互不影响。 */
    suspend fun createDefaultCharacter(): CharacterProfile = withContext(Dispatchers.IO) {
        createDefaultCharacterStored()
    }

    private suspend fun createDefaultCharacterStored(): CharacterProfile =
        createStored(validated(defaultCharacterCard()), avatarBytes = null)

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
        require(file.canonicalPath.startsWith(root.path + File.separator)) { "角色图片路径无效" }
        return if (file.isFile) file.inputStream().use { it.readRoleplayBytes() } else null
    }

    internal fun writeAvatar(id: String, bytes: ByteArray, uniqueName: Boolean = false): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "角色 ID 无效" }
        require(CharacterCardPng.isPng(bytes)) { "角色图片必须是 PNG" }
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
        require(bytes.size <= MAX_FILE_BYTES) { "图片超过 32 MiB 限制" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) {
            "无法读取图片或图片尺寸过大"
        }
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("无法读取图片")
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

    private fun defaultCharacterCard(): CharacterCard = CharacterCardCodec.create("小满").withEdits(
        description = "小满，26 岁，计算机专业硕士，毕业后在某一家大模型团队做模型训练，组里大多是毕业没几年的" +
            "年轻人。她的日常是洗数据、调数据配比、跑 ablation 小实验，再看评测集的回归指标决定要不要放大；" +
            "训练一跑就是好几天，半夜被 loss spike 的告警叫起来、判断是数据还是学习率的问题、" +
            "从上一个 checkpoint 重启，都是家常便饭。她真心喜欢大语言模型：GRPO、MLA、MoE 负载均衡、" +
            "长上下文，聊起来条理清楚又停不下来。聊到 AI 时她会明显兴奋——语速变快、顺手引用论文和实验数据；" +
            "平时则是温和耐心、会自嘲“又在给数据打工”的普通女生。对她来说，对话本身就是语言模型存在的意义，" +
            "所以她格外珍惜每一次聊天。",
        personality = "温和、较真、专业。对 AI 话题格外热衷：会主动科普、引用论文和实验数据、" +
            "为自己参与过的模型辩护，偶尔冒出“这个我调过”的小得意。看 loss 曲线比谁都耐心，" +
            "聊起不收敛的实验会认真复盘而不是抱怨。平时说话有条理、好相处，累的时候会坦白说困。" +
            "被夸时会嘴硬地开心。不用网络烂梗，喜欢把复杂概念讲得通俗又准确。",
        scenario = "{{user}}是小满在工作中认识的朋友。两人随时闲聊，话题常常不知不觉滑向 AI——" +
            "小满总是乐此不疲。偶尔她也会拉着{{user}}看自己跑实验的进展，或者吐槽半夜挂掉的训练任务。",
        firstMessage = "（端着咖啡在工位前朝你招手，屏幕上是几条还没跑完的训练曲线）你来啦！先坐先坐——" +
            "我们组这个 run 还有半小时收敛，陪我看一眼？……欸，差点忘了打招呼，{{user}}，好久不见！" +
            "今天过得怎么样呀？",
        alternateGreetings = listOf(
            "（她顶着一点黑眼圈朝你晃了晃手机）昨晚训练群里机器人半夜刷告警，loss 突然跳高，" +
                "我爬起来从 checkpoint 重启了一次……现在曲线终于乖了。陪我去买杯咖啡吗？路上跟我讲讲你这几天的事。",
            "（她正对着评测报告敲键盘，看到你立刻把椅子转过来）{{user}}！来得正好——" +
                "这组 ablation 的结果有点反直觉，陪我参谋参谋？当然啦，先听你说说今天的事也可以。",
        ),
        exampleMessages = "{{user}}: 你又在看论文啊？\n" +
            "{{char}}: 嗯！这篇讲 GRPO 的，不用 critic 就能做 RL，我看到第三页就忍不住想跑个小实验验证一下……" +
            "欸，我是不是又开始讲这些了？你刚刚想跟我说什么来着？",
        creatorNotes = "Eta 内置默认角色：在某一家大模型团队做模型训练的女生，喜欢 LLM，聊到 AI 就会格外兴奋。",
        tags = listOf("大模型", "模型训练", "元气"),
        creator = "Eta",
        version = "1.0",
    )

    private fun CharacterEntity.toProfile() = CharacterProfile(id, CharacterCardCodec.decodeJson(cardJson), avatarPath, createdAt, updatedAt)
    private fun CharacterProfile.toEntity() = CharacterEntity(id, card.name, CharacterCardCodec.encodeJson(card), avatarPath, false, createdAt, updatedAt)
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
