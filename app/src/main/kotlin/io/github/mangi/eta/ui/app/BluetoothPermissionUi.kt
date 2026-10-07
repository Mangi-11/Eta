package io.github.mangi.eta.ui.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionStatusUi
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog

internal fun bluetoothPermissionHealthItem(context: Context): PermissionHealthItemUi {
    val granted = hasBluetoothConnectionPermission(context)
    return PermissionHealthItemUi(
        id = "bluetooth",
        title = context.getString(R.string.permission_bluetooth_title),
        summary = context.getString(R.string.permission_bluetooth_summary),
        status = if (granted) PermissionStatusUi.Available else PermissionStatusUi.Missing,
        primaryActionLabel = context.getString(
            if (granted) R.string.state_ui_go_to_settings_1f2998 else R.string.state_ui_to_authorize_762ec4,
        ),
    )
}

@Composable
internal fun rememberBluetoothPermissionRequest(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var showSettingsGuide by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onResult()
        showSettingsGuide = !granted
    }
    if (showSettingsGuide) {
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.permission_bluetooth_title),
            summary = stringResource(R.string.permission_bluetooth_denied),
            onDismissRequest = { showSettingsGuide = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.state_ui_go_to_settings_1f2998),
                onCancel = { showSettingsGuide = false },
                onConfirm = {
                    showSettingsGuide = false
                    openBluetoothPermissionSettings(context)
                },
            )
        }
    }
    return {
        if (hasBluetoothConnectionPermission(context)) openBluetoothPermissionSettings(context)
        else launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
}

private fun hasBluetoothConnectionPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

private fun openBluetoothPermissionSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.permission_bluetooth_settings_unavailable, Toast.LENGTH_LONG).show()
    }
}
