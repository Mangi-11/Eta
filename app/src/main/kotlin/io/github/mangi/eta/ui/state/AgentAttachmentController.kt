package io.github.mangi.eta.ui.state

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePolicy
import io.github.mangi.eta.agent.runtime.AgentConversationIdentity
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.IncomingShareRequest
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 负责聊天输入的图片、文件、目录、绝对路径附件暂存与外部系统分享接收（IncomingShareRequest），
 * 通过会话所有权版本号防止异步解析完成时写入到已切换的新会话中。
 */
internal class AgentAttachmentController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val currentHomeState: () -> AgentChatHomeUiState,
    private val selectedConversationId: () -> String?,
    private val createConversation: () -> Unit,
    private val updateCurrentConversation: (state: AgentChatHomeUiState, updateTimestamp: Boolean) -> Unit,
) {

    private var ownerVersion = 0L

    /** 切换、新建、删除或修订会话时递增所有权版本，丢弃旧会话的异步附件结果。 */
    fun invalidatePendingOwner() {
        ownerVersion += 1
    }

    fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val staged = AgentImageCodec.stageAttachment(
                    context = appContext,
                    value = uri,
                    source = "user_attach",
                )
                val image = when (staged) {
                    is AgentImageCodec.StageResult.Success -> staged.image
                    AgentImageCodec.StageResult.InsufficientStorage -> {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                appContext,
                                appContext.getString(R.string.capability_import_insufficient_storage),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        return@launch
                    }
                    AgentImageCodec.StageResult.InvalidImage -> {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                appContext,
                                appContext.getString(R.string.state_ui_unable_to_read_this_image_please_try_again_or_us_d94978),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        return@launch
                    }
                }
                val preview = AgentImageCodec.previewFromReference(appContext, image)
                if (preview == null) {
                    deleteStagedAttachmentIfLocal(image.reference)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.state_ui_unable_to_read_this_image_please_try_again_or_us_d94978),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    // 后续发送直接对私有暂存文件开只读 fd 零拷贝传输，既脱离 Photo Picker URI 生命周期，
                    // 也不在 UI 堆内存中囤积整张原图的 Base64。
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                )
                withContext(Dispatchers.Main) {
                    val home = currentHomeState()
                    updateCurrentConversation(home.copy(pendingImages = home.pendingImages + pending), true)
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun removePendingImage(id: String) {
        val home = currentHomeState()
        updateCurrentConversation(home.copy(pendingImages = home.pendingImages.filterNot { it.id == id }), true)
    }

    fun attachFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            uris.map { uri ->
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.File,
                )
            }
        }
    }

    fun attachFolder(uri: String) {
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            listOf(
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.Directory,
                ),
            )
        }
    }

    fun attachFilePath(path: String) {
        resolveAndAttachFileReferences {
            listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path))
        }
    }

    fun removePendingFileReference(id: String) {
        val home = currentHomeState()
        updateCurrentConversation(
            home.copy(
                pendingFileReferences = home.pendingFileReferences.filterNot { it.id == id },
            ),
            true,
        )
    }

    fun acceptSharedContent(request: IncomingShareRequest) {
        val home = currentHomeState()
        val canReuseCurrentDraft = home.isConversationLoaded &&
            home.roleplay == null &&
            selectedConversationId()?.startsWith(AgentConversationIdentity.EXTERNAL_ARCHIVE_PREFIX) != true &&
            home.messages.isEmpty() &&
            !home.hasEarlierMessages &&
            !home.isStreaming &&
            !home.isCompacting &&
            home.messageEdit == null &&
            home.input.isBlank() &&
            home.pendingImages.isEmpty() &&
            home.pendingFileReferences.isEmpty() &&
            home.queuedMessages.isEmpty()
        if (!canReuseCurrentDraft) {
            createConversation()
        }
        request.text?.takeIf(String::isNotBlank)?.let { sharedText ->
            val current = currentHomeState()
            val mergedInput = when {
                current.input.isBlank() -> sharedText
                else -> current.input.trimEnd() + "\n" + sharedText
            }
            updateCurrentConversation(
                current.copy(
                    input = mergedInput,
                    inputSyncVersion = current.inputSyncVersion + 1,
                    focusInputOnSync = false,
                ),
                false,
            )
        }
        if (request.uris.isEmpty()) return
        val capturedVersion = ownerVersion
        scope.launch(Dispatchers.IO) {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            val resolvedImages = mutableListOf<PendingImageUi>()
            val resolvedFileReferences = mutableListOf<AgentFileReference>()
            val failures = mutableListOf<AgentFileReferenceGateway.Error>()
            request.uris.forEach { rawUri ->
                val uri = Uri.parse(rawUri)
                if (isSharedImageCandidate(uri, request.mimeType, isSingleUri = request.uris.size == 1)) {
                    when (
                        val staged = AgentImageCodec.stageAttachment(
                            context = appContext,
                            value = rawUri,
                            source = "user_attach",
                        )
                    ) {
                        is AgentImageCodec.StageResult.Success -> {
                            val preview = AgentImageCodec.previewFromReference(appContext, staged.image)
                            if (preview != null) {
                                resolvedImages += PendingImageUi(
                                    id = "img-${UUID.randomUUID()}",
                                    uri = staged.image.reference,
                                    dataUrl = preview.reference,
                                    mimeType = staged.image.mimeType,
                                )
                                return@forEach
                            }
                            deleteStagedAttachmentIfLocal(staged.image.reference)
                        }
                        AgentImageCodec.StageResult.InsufficientStorage -> {
                            failures += AgentFileReferenceGateway.Error.InsufficientStorage
                            return@forEach
                        }
                        AgentImageCodec.StageResult.InvalidImage -> Unit
                    }
                }
                when (val resolution = resolveSharedFileUri(gateway, uri)) {
                    is AgentFileReferenceGateway.Resolution.Success -> resolvedFileReferences += resolution.reference
                    is AgentFileReferenceGateway.Resolution.Failure -> failures += resolution.error
                }
            }
            withContext(Dispatchers.Main) {
                if (capturedVersion != ownerVersion) {
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.state_ui_conversation_switched_selected_path_not_added_5bf91e),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@withContext
                }
                val current = currentHomeState()
                val existingPaths = current.pendingFileReferences
                    .mapTo(mutableSetOf()) { it.reference.absolutePath }
                val fileAdditions = resolvedFileReferences
                    .distinctBy { it.absolutePath }
                    .filter { existingPaths.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(
                            id = "file-${UUID.randomUUID()}",
                            reference = reference,
                        )
                    }
                if (resolvedImages.isNotEmpty() || fileAdditions.isNotEmpty()) {
                    updateCurrentConversation(
                        current.copy(
                            pendingImages = current.pendingImages + resolvedImages,
                            pendingFileReferences = current.pendingFileReferences + fileAdditions,
                        ),
                        false,
                    )
                }
                val addedCount = resolvedImages.size + fileAdditions.size
                val message = when {
                    failures.size == 1 && addedCount == 0 -> failures.single().userMessage
                    failures.isNotEmpty() -> appContext.resources.getQuantityString(
                        R.plurals.file_references_added_with_failures,
                        failures.size,
                        addedCount,
                        failures.size,
                    )
                    addedCount == 0 && resolvedFileReferences.isNotEmpty() ->
                        appContext.getString(R.string.state_ui_the_selected_path_has_been_added_42b432)
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteStagedAttachmentIfLocal(reference: String) {
        if (reference.startsWith("/")) {
            runCatching { File(reference).delete() }
        }
    }

    private fun isSharedImageCandidate(
        uri: Uri,
        intentMimeType: String?,
        isSingleUri: Boolean,
    ): Boolean {
        val resolverType = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        } else {
            null
        }
        if (resolverType != null && resolverType != "*/*" && resolverType != "application/octet-stream") {
            return resolverType.startsWith("image/", ignoreCase = true)
        }
        val path = uri.lastPathSegment ?: uri.path.orEmpty()
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension in SHARED_IMAGE_EXTENSIONS) return true
        return isSingleUri && intentMimeType?.startsWith("image/", ignoreCase = true) == true
    }

    private fun resolveSharedFileUri(
        gateway: AgentFileReferenceGateway,
        uri: Uri,
    ): AgentFileReferenceGateway.Resolution = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> gateway.resolveAbsolutePath(
            rawPath = uri.path.orEmpty(),
            expectedKind = AgentFileReferenceKind.File,
        )
        else -> gateway.resolveDocumentUri(
            uri = uri,
            expectedKind = AgentFileReferenceKind.File,
        )
    }

    private fun resolveAndAttachFileReferences(
        resolver: () -> List<AgentFileReferenceGateway.Resolution>,
    ) {
        val capturedVersion = ownerVersion
        scope.launch(Dispatchers.IO) {
            val resolutions = resolver()
            val references = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Success)?.reference
            }
            val failures = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Failure)?.error
            }
            withContext(Dispatchers.Main) {
                if (capturedVersion != ownerVersion) {
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.state_ui_conversation_switched_selected_path_not_added_5bf91e),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@withContext
                }
                val current = currentHomeState()
                val existingPaths = current.pendingFileReferences
                    .mapTo(mutableSetOf()) { it.reference.absolutePath }
                val additions = references
                    .distinctBy { it.absolutePath }
                    .filter { existingPaths.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(
                            id = "file-${UUID.randomUUID()}",
                            reference = reference,
                        )
                    }
                if (additions.isNotEmpty()) {
                    updateCurrentConversation(
                        current.copy(
                            pendingFileReferences = current.pendingFileReferences + additions,
                        ),
                        true,
                    )
                }
                val message = when {
                    failures.size == 1 && references.isEmpty() -> failures.single().userMessage
                    failures.isNotEmpty() -> appContext.resources.getQuantityString(
                        R.plurals.file_references_added_with_failures,
                        failures.size,
                        additions.size,
                        failures.size,
                    )
                    additions.isEmpty() -> appContext.getString(R.string.state_ui_the_selected_path_has_been_added_42b432)
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val AgentFileReferenceGateway.Error.userMessage: String
        get() = when (this) {
            AgentFileReferenceGateway.Error.UnsupportedDocumentProvider ->
                appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.InvalidPath ->
                appContext.getString(R.string.state_ui_please_enter_a_valid_absolute_path_6afeb4)
            AgentFileReferenceGateway.Error.PathNotFound ->
                appContext.getString(R.string.state_ui_the_path_does_not_exist_or_is_no_longer_accessib_a9776e)
            AgentFileReferenceGateway.Error.UnsupportedFileType ->
                appContext.getString(R.string.state_ui_only_supports_normal_files_and_folders_4adea0)
            AgentFileReferenceGateway.Error.TypeMismatch ->
                appContext.getString(R.string.state_ui_the_selected_project_type_does_not_match_3a5c49)
            AgentFileReferenceGateway.Error.RootUnavailable ->
                appContext.getString(R.string.state_ui_root_is_not_available_and_the_path_cannot_be_ver_fc4c81)
            AgentFileReferenceGateway.Error.AccessDenied ->
                appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.ImportFailed ->
                appContext.getString(R.string.capability_import_failed)
            AgentFileReferenceGateway.Error.InsufficientStorage ->
                appContext.getString(R.string.capability_import_insufficient_storage)
            AgentFileReferenceGateway.Error.ValidationTimedOut ->
                appContext.getString(R.string.state_ui_path_verification_timed_out_please_try_again_703687)
        }

    private companion object {
        val SHARED_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
        )
    }
}
