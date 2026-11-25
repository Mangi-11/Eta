package io.github.mangi.eta.ui.state

import android.content.Context
import io.github.mangi.eta.agent.model.ConversationTitleGenerator
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.provider.AgentProviderClient
import io.github.mangi.eta.agent.model.provider.ProviderEvent
import io.github.mangi.eta.agent.model.provider.ProviderRequest
import io.github.mangi.eta.agent.model.provider.ProviderResponse
import io.github.mangi.eta.agent.model.provider.openai.OpenAiChatCompletionsProvider
import io.github.mangi.eta.agent.roleplay.CharacterCard
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.CharacterRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "zh-rCN")
class AgentAppStateConversationTitleTest {
    private lateinit var context: Context
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).declareActionUnbindable(AgentRuntimeWire.ACTION_BIND)
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        CharacterRepository.initialize(context)
        seedProvider()
    }

    @After
    fun tearDown() {
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        scope.cancel()
        awaitCondition { job.isCompleted }
        EtaDatabase.closeForTests()
    }

    @Test
    fun roleplayConversationGeneratesTitleOnceAfterFirstTurnAndSupportsCharacterSearch() {
        val generationCalls = AtomicInteger(0)
        val generatedTitles = ArrayDeque(listOf("雨夜书店初遇", "不应被改写的标题"))
        val state = AgentAppState(
            context = context,
            scope = scope,
            titleGenerator = fakeTitleGenerator(generationCalls) { generatedTitles.removeFirstOrNull() },
        )
        awaitCondition { state.modelPickerState.selectedModel != null }

        state.startCharacterConversation(
            binding = sampleBinding(name = "莉莉丝"),
            greeting = "雨声敲打着旧书店的玻璃门，你推门走了进来。",
        )
        val conversationId = checkNotNull(state.conversationPaneState.selectedConversationId)
        assertEquals("莉莉丝", state.currentConversationTitle)
        assertEquals("莉莉丝", summaryFor(state, conversationId).characterName)

        // 首轮发送后立即用首条消息摘要作为临时/回退标题，侧栏继续保留角色名。
        state.sendCurrentMessage("我收起雨伞，问她有没有关于星图的手抄本")
        assertEquals("我收起雨伞，问她有没有关于星图的手抄本", state.currentConversationTitle)
        assertEquals("莉莉丝", summaryFor(state, conversationId).characterName)
        awaitCondition { !state.homeState.isStreaming }

        // 首轮回复完成后生成主题标题。
        state.maybeGenerateConversationTitle(conversationId, "柜台后的少女抬起头，取出一本泛黄的星图笔记本。")
        awaitCondition { state.currentConversationTitle == "雨夜书店初遇" }
        assertEquals(1, generationCalls.get())
        val updatedSummary = summaryFor(state, conversationId)
        assertEquals("雨夜书店初遇", updatedSummary.title)
        assertEquals("莉莉丝", updatedSummary.characterName)

        // 标题变为主题后，按角色名搜索侧栏依然能命中该会话。
        state.updateSearchQuery("莉莉丝")
        awaitCondition { state.conversationPaneState.conversations.any { it.id == conversationId } }
        state.updateSearchQuery("")

        // 后续轮次完成后不再改写标题。
        state.sendCurrentMessage("我翻开第一页仔细查看里面的批注")
        awaitCondition { !state.homeState.isStreaming }
        state.maybeGenerateConversationTitle(conversationId, "书页边缘用银墨水写着一段关于钟楼的坐标。")
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        assertEquals(1, generationCalls.get())
        assertEquals("雨夜书店初遇", state.currentConversationTitle)
    }

    @Test
    fun roleplayConversationRenamedBeforeFirstTurnIsNotOverwritten() {
        val generationCalls = AtomicInteger(0)
        val state = AgentAppState(
            context = context,
            scope = scope,
            titleGenerator = fakeTitleGenerator(generationCalls) { "不应该覆盖" },
        )
        awaitCondition { state.modelPickerState.selectedModel != null }

        state.startCharacterConversation(
            binding = sampleBinding(name = "艾塔"),
            greeting = "晚上好，今天想聊什么？",
        )
        val conversationId = checkNotNull(state.conversationPaneState.selectedConversationId)
        state.renameConversation(conversationId, "我的专属支线")

        state.sendCurrentMessage("先聊聊今天的计划")
        awaitCondition { !state.homeState.isStreaming }
        state.maybeGenerateConversationTitle(conversationId, "好的，我们从最重要的事项开始整理。")
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

        assertEquals(0, generationCalls.get())
        assertEquals("我的专属支线", state.currentConversationTitle)
    }

    @Test
    fun failedTitleGenerationOnFirstTurnDoesNotTriggerAgainOnSecondTurn() {
        val generationCalls = AtomicInteger(0)
        val responses = ArrayDeque<String?>(listOf(null, "延迟触发的标题"))
        val state = AgentAppState(
            context = context,
            scope = scope,
            titleGenerator = fakeTitleGenerator(generationCalls) { responses.removeFirstOrNull() },
        )
        awaitCondition { state.modelPickerState.selectedModel != null }

        state.startCharacterConversation(
            binding = sampleBinding(name = "赛琳"),
            greeting = "欢迎来到天文台。",
        )
        val conversationId = checkNotNull(state.conversationPaneState.selectedConversationId)

        state.sendCurrentMessage("今晚能看到木星吗")
        awaitCondition { !state.homeState.isStreaming }
        state.maybeGenerateConversationTitle(conversationId, "今晚大气视宁度很好，木星的四颗伽利略卫星非常清晰。")
        awaitCondition { generationCalls.get() == 1 }
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        assertEquals("今晚能看到木星吗", state.currentConversationTitle)

        // 首轮失败或未启用后，第二轮完成不能消耗残留标记去改写标题。
        state.sendCurrentMessage("帮我调整望远镜焦距")
        awaitCondition { !state.homeState.isStreaming }
        state.maybeGenerateConversationTitle(conversationId, "已经微调好了目镜焦距，现在可以清楚看到条纹。")
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

        assertEquals(1, generationCalls.get())
        assertEquals("今晚能看到木星吗", state.currentConversationTitle)
    }

    @Test
    fun roleplayImageOnlyFirstTurnKeepsCharacterNameUntilRecoveryGeneratesTitle() {
        val generationCalls = AtomicInteger(0)
        val binding = sampleBinding(name = "米娅")
        val initialState = AgentChatHomeUiState(
            roleplay = binding,
            messages = listOf(
                AgentMessageUi(id = "greeting-1", content = "今天拍了什么照片？", characterEditable = true),
                UserMessageUi(id = "user-1", content = "", images = listOf("data:image/jpeg;base64,AA==")),
            ),
            history = emptyList(),
            journal = emptyList(),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
            isConversationLoaded = true,
            modelId = "model-1",
        )
        val snapshot = AgentConversationStore.Snapshot(
            selectedConversationId = "conv-img",
            conversationsById = mapOf("conv-img" to initialState),
            titles = mapOf("conv-img" to "米娅"),
            updatedAt = mapOf("conv-img" to 1L),
        )
        val state = AgentAppState(
            context = context,
            scope = scope,
            initialConversations = snapshot,
            titleGenerator = fakeTitleGenerator(generationCalls) { "日落海边照片分享" },
        )

        state.maybeGenerateConversationTitle("conv-img", "这片日落海滩的晚霞太美了，海面全都是橘金色的倒影。")
        awaitCondition { state.currentConversationTitle == "日落海边照片分享" }
        assertEquals(1, generationCalls.get())
    }

    @Test
    fun pagedConversationWithEarlierMessagesDoesNotRegenerateTitle() {
        val generationCalls = AtomicInteger(0)
        val binding = sampleBinding(name = "诺亚")
        val pagedState = AgentChatHomeUiState(
            roleplay = binding,
            messages = listOf(UserMessageUi(id = "user-last", content = "接下来去哪里")),
            history = emptyList(),
            journal = emptyList(),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
            hasEarlierMessages = true,
            isConversationLoaded = true,
            modelId = "model-1",
        )
        val snapshot = AgentConversationStore.Snapshot(
            selectedConversationId = "conv-paged",
            conversationsById = mapOf("conv-paged" to pagedState),
            titles = mapOf("conv-paged" to "接下来去哪里"),
            updatedAt = mapOf("conv-paged" to 1L),
        )
        val state = AgentAppState(
            context = context,
            scope = scope,
            initialConversations = snapshot,
            titleGenerator = fakeTitleGenerator(generationCalls) { "不该覆盖分页长对话" },
        )

        state.maybeGenerateConversationTitle("conv-paged", "我们去港口看看。")
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

        assertEquals(0, generationCalls.get())
        assertEquals("接下来去哪里", state.currentConversationTitle)
    }

    @Test
    fun characterRenameSyncsLoadedAndUnloadedConversationsImmediatelyAndPersists() {
        val profile = runBlocking {
            CharacterRepository.create(
                CharacterCardCodec.create("莉莉丝").withEdits(description = "初始设定"),
            )
        }
        val binding = runBlocking { CharacterRepository.binding(profile.id) }
        val loadedThemed = AgentChatHomeUiState(
            roleplay = binding,
            messages = listOf(
                UserMessageUi(id = "u-1", content = "我走进雨夜里的旧书店"),
                AgentMessageUi(id = "a-1", content = "欢迎光临。"),
            ),
            history = emptyList(),
            journal = emptyList(),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
            modelId = "model-1",
        )
        val unloadedDefault = AgentChatHomeUiState(
            roleplay = binding,
            messages = listOf(AgentMessageUi(id = "g-2", content = "你好呀", characterEditable = true)),
            history = emptyList(),
            journal = emptyList(),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
            modelId = "model-1",
        )
        val unloadedThemed = AgentChatHomeUiState(
            roleplay = binding,
            messages = listOf(
                UserMessageUi(id = "u-3", content = "我们去钟楼看看"),
                AgentMessageUi(id = "a-3", content = "好，跟紧我。"),
            ),
            history = emptyList(),
            journal = emptyList(),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
            modelId = "model-1",
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-loaded",
                conversationsById = mapOf(
                    "conv-loaded" to loadedThemed,
                    "conv-unloaded-default" to unloadedDefault,
                    "conv-unloaded-themed" to unloadedThemed,
                ),
                titles = mapOf(
                    "conv-loaded" to "雨夜书店初遇",
                    "conv-unloaded-default" to "莉莉丝",
                    "conv-unloaded-themed" to "旧城钟楼探秘",
                ),
                updatedAt = mapOf(
                    "conv-loaded" to 3L,
                    "conv-unloaded-default" to 2L,
                    "conv-unloaded-themed" to 1L,
                ),
            )
        }

        val state = AgentAppState(context = context, scope = scope)
        awaitCondition {
            state.homeState.isConversationLoaded &&
                state.homeState.roleplay?.characterName == "莉莉丝" &&
                summaryFor(state, "conv-unloaded-themed").characterName == "莉莉丝"
        }

        runBlocking {
            CharacterRepository.save(profile.copy(card = profile.card.withEdits(name = "月见")))
        }

        awaitCondition {
            state.homeState.roleplay?.characterName == "月见" &&
                summaryFor(state, "conv-loaded").characterName == "月见" &&
                summaryFor(state, "conv-unloaded-default").characterName == "月见" &&
                summaryFor(state, "conv-unloaded-themed").characterName == "月见"
        }
        assertEquals("雨夜书店初遇", state.currentConversationTitle)
        assertEquals("雨夜书店初遇", summaryFor(state, "conv-loaded").title)
        assertEquals("月见", summaryFor(state, "conv-unloaded-default").title)
        assertEquals("旧城钟楼探秘", summaryFor(state, "conv-unloaded-themed").title)

        state.updateSearchQuery("月见")
        awaitCondition {
            state.conversationPaneState.conversations.map { it.id }.toSet() ==
                setOf("conv-loaded", "conv-unloaded-default", "conv-unloaded-themed")
        }
        state.updateSearchQuery("")

        state.selectConversation("conv-unloaded-default")
        awaitCondition {
            state.conversationPaneState.selectedConversationId == "conv-unloaded-default" &&
                state.homeState.isConversationLoaded
        }
        assertEquals("月见", state.homeState.roleplay?.characterName)
        assertEquals("月见", state.currentConversationTitle)

        state.selectConversation("conv-unloaded-themed")
        awaitCondition {
            state.conversationPaneState.selectedConversationId == "conv-unloaded-themed" &&
                state.homeState.isConversationLoaded
        }
        assertEquals("月见", state.homeState.roleplay?.characterName)
        assertEquals("旧城钟楼探秘", state.currentConversationTitle)

        val reloaded = AgentConversationStore.load(context)
        assertEquals("雨夜书店初遇", reloaded.titles["conv-loaded"])
        assertEquals("月见", reloaded.titles["conv-unloaded-default"])
        assertEquals("旧城钟楼探秘", reloaded.titles["conv-unloaded-themed"])
        assertEquals("月见", reloaded.conversationsById["conv-loaded"]?.conversationCharacterName)
        assertEquals("月见", reloaded.conversationsById["conv-unloaded-default"]?.conversationCharacterName)
        assertEquals("月见", reloaded.conversationsById["conv-unloaded-themed"]?.roleplay?.characterName)
    }

    @Test
    fun emptyRoleplayConversationExposesTitleForTopBar() {
        val state = AgentAppState(context = context, scope = scope)
        awaitCondition { state.modelPickerState.selectedModel != null }

        state.startCharacterConversation(
            binding = sampleBinding(name = "林溪"),
            greeting = "",
        )
        val conversationId = checkNotNull(state.conversationPaneState.selectedConversationId)
        assertEquals("林溪", state.currentConversationTitle)

        state.renameConversation(conversationId, "平行线 A")
        assertEquals("平行线 A", state.currentConversationTitle)
    }

    private fun summaryFor(state: AgentAppState, conversationId: String) =
        state.conversationPaneState.conversations.first { it.id == conversationId }

    private fun sampleBinding(name: String): RoleplayBinding {
        val card = CharacterCard(
            JsonObject(
                mapOf(
                    "spec" to JsonPrimitive("chara_card_v2"),
                    "data" to JsonObject(mapOf("name" to JsonPrimitive(name))),
                ),
            ),
        )
        return RoleplayBinding(
            characterId = "char-$name",
            cardSnapshotJson = CharacterCardCodec.encodeJson(card),
            characterName = name,
        )
    }

    private fun fakeTitleGenerator(
        calls: AtomicInteger,
        nextTitle: () -> String?,
    ): ConversationTitleGenerator {
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://fixture.invalid/v1",
            apiKey = "fixture",
            model = "fixture-small",
            systemPrompt = "",
            reasoningEffort = ReasoningEffort.OFF,
            thinkingEnabled = false,
        )
        return ConversationTitleGenerator(
            loadConfig = { config },
            providerFor = {
                object : AgentProviderClient {
                    override val id = "fixture"
                    override val capabilities = OpenAiChatCompletionsProvider.capabilities
                    override fun complete(
                        request: ProviderRequest,
                        runController: AgentRunController,
                        onEvent: (ProviderEvent) -> Unit,
                    ): ProviderResponse {
                        calls.incrementAndGet()
                        val title = nextTitle()
                        return ProviderResponse(
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", title.orEmpty())
                                .put("finish_reason", if (title == null) "length" else "stop"),
                        )
                    }
                }
            },
        )
    }

    private fun seedProvider() = runBlocking {
        ProviderRepository.addProvider(
            OpenAiCompatibleProviderSetting(
                id = "provider-test",
                name = "Test Provider",
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                models = listOf(Model(id = "model-1", modelId = "test-model", displayName = "Test Model")),
            ),
        )
        RuntimeConfigRepository.setSelectedModelId("model-1")
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            Thread.sleep(1)
        }
        assertTrue("条件未在限定时间内满足", condition())
    }
}
