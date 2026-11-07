package io.github.mangi.eta.ui.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.model.LOCAL_NETWORK_PERMISSION_ITEM_ID
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionStatusUi
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog

internal fun localNetworkPermissionHealthItem(context: Context): PermissionHealthItemUi? {
    val access = LocalNetworkPermission.accessState(context)
    if (access == LocalNetworkPermission.AccessState.NOT_REQUIRED) return null
    val granted = access == LocalNetworkPermission.AccessState.GRANTED
    return PermissionHealthItemUi(
        id = LOCAL_NETWORK_PERMISSION_ITEM_ID,
        title = context.getString(R.string.permission_local_network_title),
        summary = context.getString(R.string.permission_local_network_summary),
        status = if (granted) PermissionStatusUi.Available else PermissionStatusUi.Missing,
        primaryActionLabel = context.getString(
            if (granted) R.string.state_ui_go_to_settings_1f2998 else R.string.state_ui_to_authorize_762ec4,
        ),
    )
}

@Composable
internal fun rememberLocalNetworkPermissionRequest(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var showSettingsGuide by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onResult()
        showSettingsGuide = !granted
    }
    if (showSettingsGuide) {
        val onConfirmSettings: () -> Unit = {
            showSettingsGuide = false
            openLocalNetworkPermissionSettings(context)
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.permission_local_network_title),
                    message = stringResource(R.string.permission_local_network_denied),
                    confirmText = stringResource(R.string.state_ui_go_to_settings_1f2998),
                    onConfirm = onConfirmSettings,
                    onDismissRequest = { showSettingsGuide = false },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    cornerRadius = DialogDefaults.CornerRadius,
                    title = stringResource(R.string.permission_local_network_title),
                    summary = stringResource(R.string.permission_local_network_denied),
                    onDismissRequest = { showSettingsGuide = false },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.state_ui_go_to_settings_1f2998),
                        onCancel = { showSettingsGuide = false },
                        onConfirm = onConfirmSettings,
                    )
                }
            }
        }
    }
    return {
        when (LocalNetworkPermission.accessState(context)) {
            LocalNetworkPermission.AccessState.NOT_REQUIRED -> onResult()
            LocalNetworkPermission.AccessState.GRANTED -> openLocalNetworkPermissionSettings(context)
            LocalNetworkPermission.AccessState.DENIED -> launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}

private fun openLocalNetworkPermissionSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.permission_local_network_settings_unavailable, Toast.LENGTH_LONG).show()
    }
}
