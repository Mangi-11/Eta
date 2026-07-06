package fuck.andes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as LucideR
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/**
 * 二级页面统一返回按钮。参考 InstallerX-Revived 的 MiuixBackButton。
 * 图标用 lucide chevron-left（项目未依赖 miuix-icons-extended）。
 */
@Composable
fun MiuixBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_chevron_left),
            contentDescription = "返回",
        )
    }
}
