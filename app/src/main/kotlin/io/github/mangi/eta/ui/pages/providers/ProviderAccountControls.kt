package io.github.mangi.eta.ui.pages.providers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProviderAccountControls(
    state: ProviderLoginState,
    authMode: String,
    enabled: Boolean,
    browserError: String?,
    onOpenBrowser: () -> Unit,
    onManageUsage: () -> Unit,
    onLogin: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
) {
    val prompt = state.prompt

    val account = state.account
    val loggedIn = account != null && !account.needsReauthentication
    val needsLogin = !loggedIn || !account.planEnabled
    val brand = if (authMode == ProviderAuthModes.XAI_GROK) "Grok" else "Codex"
    EtaPreference(
        title = account?.email ?: account?.displayName ?: "$brand 账号",
        summary = browserError ?: state.message ?: when (state.phase) {
            ProviderLoginPhase.Loading -> "正在读取登录状态…"
            ProviderLoginPhase.Saving -> "正在保存配置…"
            ProviderLoginPhase.Disconnecting -> "正在退出登录…"
            ProviderLoginPhase.Authorizing -> if (prompt == null) "正在准备授权…" else "请在浏览器中完成授权"
            ProviderLoginPhase.SyncingModels -> "登录成功，正在拉取最新模型…"
            ProviderLoginPhase.Idle -> when {
                account == null -> if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                    "使用 ChatGPT 账号登录 Codex"
                } else {
                    "登录后使用 Grok 订阅额度"
                }
                account.needsReauthentication -> "请重新登录"
                !account.planEnabled -> "账号尚未授予模型调用权限"
                authMode == ProviderAuthModes.OPENAI_CHATGPT -> "已登录 Codex"
                else -> "已登录 Grok"
            }
        },
    )
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        prompt?.userCode?.let { userCode ->
            SelectionContainer {
                Text(
                    text = userCode,
                    style = MiuixTheme.textStyles.headline1,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isBusy) {
                if (prompt != null) {
                    EtaTextButton(text = "打开浏览器", onClick = onOpenBrowser, modifier = Modifier.weight(1f))
                }
                if (state.phase in setOf(ProviderLoginPhase.Saving, ProviderLoginPhase.Authorizing, ProviderLoginPhase.SyncingModels)) {
                    EtaTextButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
                }
            } else {
                if (needsLogin) {
                    EtaTextButton(
                        text = if (loggedIn) "重新授权" else "使用 $brand 登录",
                        enabled = enabled,
                        onClick = onLogin,
                        modifier = Modifier.weight(1f),
                    )
                } else if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                    EtaTextButton(text = "管理用量", enabled = enabled, onClick = onManageUsage, modifier = Modifier.weight(1f))
                }
                if (loggedIn) {
                    EtaTextButton(text = "退出登录", enabled = enabled, onClick = onDisconnect, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

internal fun openProviderAccountLink(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    "未找到可用浏览器"
} catch (_: SecurityException) {
    "无法打开浏览器，请检查系统设置"
}
