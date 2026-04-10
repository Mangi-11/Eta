package fuck.andes

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import io.github.libxposed.api.XposedModule
import java.io.File
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets

internal object GoogleAppHooks {
    private const val FAST_MODE_ID = "56fdd199312815e2"
    private const val THINKING_MODE_ID = "e051ce1aa80aa576"
    private const val PRO_MODE_ID = "e6fa609c3fa255c0"
    private const val FULLSCREEN_ZERO_STATE_MODE_FILE = "FullscreenZeroStateModeDataStore.pb"
    private const val ROBIN_MODE_FILE = "RobinModeDataStore.pb"
    private const val TEMP_CHAT_TOGGLE_CLICK_LISTENER = "asfs"
    private const val IN_CHAT_NEW_CHAT_CLICK_LISTENER = "awmv"
    private const val IN_CHAT_NEW_CHAT_CLICK_CASE = 0
    private val proModePayload = byteArrayOf(0x0A, 0x10) + PRO_MODE_ID.toByteArray(StandardCharsets.US_ASCII)
    private val robinModeOrder = listOf(PRO_MODE_ID, THINKING_MODE_ID, FAST_MODE_ID)

    private val mainChatEntryActivityNames = listOf(
        "com.google.android.apps.search.assistant.surfaces.voice.deeplinks.handlers.gateway.impl.GeminiGatewayActivity",
        "com.google.android.apps.search.assistant.surfaces.voice.robin.launcher.RobinEntryPointActivity",
        "com.google.android.apps.search.assistant.surfaces.voice.robin.launcher.RobinShellAppEntryPointActivity",
        "com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity"
    )

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        // 保留 Google 进程内机型伪装，避免影响用户现有的一圈即搜能力。
        setBuildField(logger, Build::class.java, "MANUFACTURER", "samsung")
        setBuildField(logger, Build::class.java, "BRAND", "samsung")
        setBuildField(logger, Build::class.java, "MODEL", "SM-S928B")
        setBuildField(logger, Build::class.java, "PRODUCT", "e3s")
        setBuildField(logger, Build::class.java, "DEVICE", "e3s")
        installMainChatEntryHooks(module, logger, classLoader)
        installTempChatToggleHook(module, logger, classLoader)
        installInChatNewChatHook(module, logger, classLoader)
        installModeSelectionHooks(module, logger, classLoader)
    }

    private fun installMainChatEntryHooks(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        mainChatEntryActivityNames.forEach { className ->
            val activityClass = HookSupport.findClassOrNull(classLoader, className) ?: return@forEach
            hookEntryMethod(
                module = module,
                logger = logger,
                activityClass = activityClass,
                className = className,
                methodName = "onCreate",
                parameterTypes = arrayOf(Bundle::class.java)
            )
            hookEntryMethod(
                module = module,
                logger = logger,
                activityClass = activityClass,
                className = className,
                methodName = "onNewIntent",
                parameterTypes = arrayOf(Intent::class.java)
            )
        }
    }

    private fun hookEntryMethod(
        module: XposedModule,
        logger: ModuleLogger,
        activityClass: Class<*>,
        className: String,
        methodName: String,
        parameterTypes: Array<Class<*>>
    ) {
        val method = HookSupport.findMethod(activityClass, methodName, *parameterTypes) ?: return
        val description = "$className.$methodName(${parameterTypes.joinToString { it.simpleName }})"
        HookSupport.hookMethod(module, logger, method, description) { chain ->
            val currentClassName = chain.getThisObject()?.javaClass?.name
            if (currentClassName == className) {
                patchMainChatDefaultMode(logger)
            }
            chain.proceed()
        }
    }

    private fun installTempChatToggleHook(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        installDefaultModeClickHook(
            module = module,
            logger = logger,
            classLoader = classLoader,
            listenerClassName = TEMP_CHAT_TOGGLE_CLICK_LISTENER,
            description = "asfs.onClick(temp chat toggle)"
        )
    }

    private fun installInChatNewChatHook(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        installDefaultModeClickHook(
            module = module,
            logger = logger,
            classLoader = classLoader,
            listenerClassName = IN_CHAT_NEW_CHAT_CLICK_LISTENER,
            description = "awmv.onClick(in-chat new chat)",
            shouldPrime = ::shouldPrimeInChatNewChat
        )
    }

    private fun installDefaultModeClickHook(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader,
        listenerClassName: String,
        description: String,
        shouldPrime: (Any?) -> Boolean = { true }
    ) {
        val listenerClass = HookSupport.findClassOrNull(classLoader, listenerClassName) ?: return
        val onClick = HookSupport.findDeclaredMethod(listenerClass, "onClick", View::class.java) ?: return
        HookSupport.hookMethod(module, logger, onClick, description) { chain ->
            val shouldPatch = shouldPrime(chain.getThisObject())
            if (shouldPatch) {
                patchMainChatDefaultMode(logger)
            }
            val result = chain.proceed()
            if (shouldPatch) {
                patchMainChatDefaultMode(logger)
            }
            result
        }
    }

    private fun shouldPrimeInChatNewChat(listener: Any?): Boolean {
        val target = listener ?: return false
        val clickCase = HookSupport.getFieldValue(target, "b") as? Int ?: return false
        if (clickCase != IN_CHAT_NEW_CHAT_CLICK_CASE) return false

        val owner = HookSupport.getFieldValue(target, "a") ?: return false
        return owner.javaClass.name == "awmw"
    }

    private fun installModeSelectionHooks(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val bardModeIdClass = HookSupport.findClassOrNull(classLoader, "aley") ?: return
        val continuationClass = HookSupport.findClassOrNull(classLoader, "frdz") ?: return

        HookSupport.findClassOrNull(classLoader, "ajex")
            ?.let { HookSupport.findDeclaredMethod(it, "b", bardModeIdClass, continuationClass) }
            ?.let { method ->
                HookSupport.hookMethod(module, logger, method, "ajex.b(BardModeId)") { chain ->
                    val overriddenArgs = buildForcedModeArgs(chain, bardModeIdClass)
                    if (overriddenArgs != null) {
                        chain.proceed(overriddenArgs)
                    } else {
                        chain.proceed()
                    }
                }
            }
    }

    private fun buildForcedModeArgs(
        chain: io.github.libxposed.api.XposedInterface.Chain,
        bardModeIdClass: Class<*>
    ): Array<Any?>? {
        if (extractModeId(chain.getArg(0)) == PRO_MODE_ID) return null
        val overriddenMode = createBardModeId(bardModeIdClass, PRO_MODE_ID) ?: return null
        val args = chain.getArgs().toTypedArray()
        args[0] = overriddenMode
        return args
    }

    private fun extractModeId(mode: Any?): String? =
        HookSupport.getFieldValue(mode ?: return null, "a") as? String

    private fun createBardModeId(bardModeIdClass: Class<*>, modeId: String): Any? =
        runCatching {
            bardModeIdClass.getDeclaredConstructor(String::class.java)
                .apply { isAccessible = true }
                .newInstance(modeId)
        }.getOrNull()

    private fun patchMainChatDefaultMode(logger: ModuleLogger) {
        resolveGoogleAccountsDirs().forEach { accountsDir ->
            accountsDir.listFiles()
                ?.filter(File::isDirectory)
                ?.sortedBy(File::getName)
                ?.forEach { accountDir ->
                    patchFullscreenZeroStateMode(logger, accountDir)
                    patchRobinModeDataStore(logger, accountDir)
                }
        }
    }

    private fun resolveGoogleAccountsDirs(): List<File> =
        listOf(
            File("/data/user/0/${ModuleConfig.GOOGLE_PACKAGE}/files/accounts"),
            File("/data/data/${ModuleConfig.GOOGLE_PACKAGE}/files/accounts")
        ).filter { it.exists() }.distinctBy { it.absolutePath }

    private fun patchFullscreenZeroStateMode(logger: ModuleLogger, accountDir: File) {
        val target = File(accountDir, FULLSCREEN_ZERO_STATE_MODE_FILE)
        if (!target.isFile) return

        val currentBytes = runCatching { target.readBytes() }.getOrElse { throwable ->
            logger.warnThrottled(
                key = "gsa-read-mode-${target.absolutePath}",
                message = "GSA default Pro: 读取 ${target.absolutePath} 失败: ${throwable.javaClass.simpleName}"
            )
            return
        }
        if (currentBytes.contentEquals(proModePayload)) return

        runCatching { target.writeBytes(proModePayload) }
            .onSuccess { }
            .onFailure { throwable ->
                logger.warnThrottled(
                    key = "gsa-write-mode-${target.absolutePath}",
                    message = "GSA default Pro: 写入 ${target.absolutePath} 失败: ${throwable.javaClass.simpleName}"
                )
            }
    }

    private fun patchRobinModeDataStore(logger: ModuleLogger, accountDir: File) {
        val target = File(accountDir, ROBIN_MODE_FILE)
        if (!target.isFile) return

        val currentBytes = runCatching { target.readBytes() }.getOrElse { throwable ->
            logger.warnThrottled(
                key = "gsa-read-robin-mode-${target.absolutePath}",
                message = "GSA default Pro: 读取 ${target.absolutePath} 失败: ${throwable.javaClass.simpleName}"
            )
            return
        }
        val patchedBytes = rewriteRobinModePayload(currentBytes) ?: return
        if (patchedBytes.contentEquals(currentBytes)) return

        runCatching { target.writeBytes(patchedBytes) }
            .onSuccess { }
            .onFailure { throwable ->
                logger.warnThrottled(
                    key = "gsa-write-robin-mode-${target.absolutePath}",
                    message = "GSA default Pro: 写入 ${target.absolutePath} 失败: ${throwable.javaClass.simpleName}"
                )
            }
    }

    private fun rewriteRobinModePayload(currentBytes: ByteArray): ByteArray? {
        val fields = parseProtoFields(currentBytes) ?: return null
        val reorderedField1 = reorderModeFields(fields, 1) ?: return null
        val reorderedField4 = reorderModeFields(fields, 4) ?: return null
        val field1Changed = fields.filter { it.number == 1 } != reorderedField1
        val field4Changed = fields.filter { it.number == 4 } != reorderedField4

        var changed = field1Changed || field4Changed
        val rewrittenFields = mutableListOf<ProtoField>()
        var field1Inserted = false
        var field4Inserted = false

        fields.forEach { field ->
            when (field.number) {
                1 -> {
                    if (!field1Inserted) {
                        rewrittenFields += reorderedField1
                        field1Inserted = true
                    }
                }

                2 -> {
                    val updatedField = updateModeKeyField(field)
                    if (updatedField != field) {
                        changed = true
                    }
                    rewrittenFields += updatedField
                }

                4 -> {
                    if (!field4Inserted) {
                        rewrittenFields += reorderedField4
                        field4Inserted = true
                    }
                }

                else -> rewrittenFields += field
            }
        }

        return if (changed) encodeProtoFields(rewrittenFields) else null
    }

    private fun reorderModeFields(fields: List<ProtoField>, fieldNumber: Int): List<ProtoField>? {
        val modeFields = fields.filter { it.number == fieldNumber && it.wireType == ProtoWireType.LENGTH_DELIMITED }
        if (modeFields.isEmpty()) return null

        val fieldByModeId = modeFields.associateBy { it.asUtf8String() }
        val reordered = robinModeOrder.mapNotNull(fieldByModeId::get)
        return if (reordered.size == modeFields.size) reordered else null
    }

    private fun updateModeKeyField(field: ProtoField): ProtoField {
        if (field.wireType != ProtoWireType.LENGTH_DELIMITED) return field
        val value = field.asUtf8String()
        val separatorIndex = value.lastIndexOf('_')
        if (separatorIndex < 0) return field
        val modeId = value.substring(separatorIndex + 1)
        if (modeId !in robinModeOrder || modeId == PRO_MODE_ID) return field
        return ProtoField.string(field.number, value.substring(0, separatorIndex + 1) + PRO_MODE_ID)
    }

    private fun parseProtoFields(bytes: ByteArray): List<ProtoField>? {
        val fields = mutableListOf<ProtoField>()
        var cursor = 0
        while (cursor < bytes.size) {
            val key = readVarint(bytes, cursor) ?: return null
            val fieldNumber = (key.value ushr 3).toInt()
            val wireType = ProtoWireType.fromValue((key.value and 0x07).toInt()) ?: return null
            cursor = key.nextIndex
            when (wireType) {
                ProtoWireType.VARINT -> {
                    val value = readVarint(bytes, cursor) ?: return null
                    fields += ProtoField.varint(fieldNumber, value.value)
                    cursor = value.nextIndex
                }

                ProtoWireType.LENGTH_DELIMITED -> {
                    val length = readVarint(bytes, cursor) ?: return null
                    cursor = length.nextIndex
                    val end = cursor + length.value.toInt()
                    if (end > bytes.size) return null
                    fields += ProtoField.bytes(fieldNumber, bytes.copyOfRange(cursor, end))
                    cursor = end
                }
            }
        }
        return fields
    }

    private fun encodeProtoFields(fields: List<ProtoField>): ByteArray {
        val output = ArrayList<Byte>()
        fields.forEach { field ->
            output += encodeVarint((field.number.toLong() shl 3) or field.wireType.value.toLong()).toList()
            when (field.wireType) {
                ProtoWireType.VARINT -> {
                    output += encodeVarint(field.varintValue ?: 0L).toList()
                }

                ProtoWireType.LENGTH_DELIMITED -> {
                    val value = field.bytesValue ?: ByteArray(0)
                    output += encodeVarint(value.size.toLong()).toList()
                    output += value.toList()
                }
            }
        }
        return output.toByteArray()
    }

    private fun readVarint(bytes: ByteArray, startIndex: Int): VarintReadResult? {
        var index = startIndex
        var value = 0L
        var shift = 0
        while (index < bytes.size && shift < Long.SIZE_BITS) {
            val current = bytes[index].toInt() and 0xFF
            value = value or ((current and 0x7F).toLong() shl shift)
            index += 1
            if (current and 0x80 == 0) {
                return VarintReadResult(value = value, nextIndex = index)
            }
            shift += 7
        }
        return null
    }

    private fun encodeVarint(value: Long): ByteArray {
        var current = value
        val bytes = ArrayList<Byte>()
        while (current and 0x7FL.inv() != 0L) {
            bytes += (((current and 0x7F) or 0x80).toInt().toByte())
            current = current ushr 7
        }
        bytes += current.toByte()
        return bytes.toByteArray()
    }

    private fun setBuildField(
        logger: ModuleLogger,
        clazz: Class<*>,
        fieldName: String,
        value: String
    ) {
        val field = runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }
        }.getOrElse { throwable ->
            logger.error("GSA: 找不到 Build.$fieldName", throwable)
            return
        }

        runCatching {
            field.set(null, value)
        }.recoverCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply {
                isAccessible = true
            }.get(null)
            val base = unsafeClass.getDeclaredMethod("staticFieldBase", Field::class.java)
                .invoke(theUnsafe, field)
            val offset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field::class.java)
                .invoke(theUnsafe, field) as Long
            unsafeClass.getDeclaredMethod(
                "putObjectVolatile",
                Any::class.java,
                Long::class.javaPrimitiveType!!,
                Any::class.java
            ).invoke(theUnsafe, base, offset, value)
        }.onFailure { throwable ->
            logger.error("GSA: 修改 Build.$fieldName 失败", throwable)
        }
    }

    private data class VarintReadResult(
        val value: Long,
        val nextIndex: Int
    )

    private data class ProtoField(
        val number: Int,
        val wireType: ProtoWireType,
        val varintValue: Long? = null,
        val bytesValue: ByteArray? = null
    ) {
        fun asUtf8String(): String = (bytesValue ?: ByteArray(0)).toString(StandardCharsets.UTF_8)

        companion object {
            fun varint(number: Int, value: Long): ProtoField =
                ProtoField(number = number, wireType = ProtoWireType.VARINT, varintValue = value)

            fun bytes(number: Int, value: ByteArray): ProtoField =
                ProtoField(number = number, wireType = ProtoWireType.LENGTH_DELIMITED, bytesValue = value)

            fun string(number: Int, value: String): ProtoField =
                bytes(number, value.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private enum class ProtoWireType(val value: Int) {
        VARINT(0),
        LENGTH_DELIMITED(2);

        companion object {
            fun fromValue(value: Int): ProtoWireType? =
                entries.firstOrNull { it.value == value }
        }
    }
}
