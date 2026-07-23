package fuck.andes.ui.pages.providers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.R
import fuck.andes.data.model.CustomProviderSetting
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.provider.ProviderSourceRegistry
import fuck.andes.ui.components.IconTintBlue
import fuck.andes.ui.components.IconTintGreen
import fuck.andes.ui.components.StatusWarning
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 分组标题 + 卡片的标准组合，Provider 相关页面统一使用。 */
@Composable
internal fun ProviderSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (title != null) {
            SmallTitle(title)
        }
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            content()
        }
    }
}

/** ColorOS 风格圆形彩色图标（32dp 圆形底 + 纯白图标），与设置页视觉一致。 */
@Composable
internal fun ProviderRoundIcon(
    @DrawableRes icon: Int,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(end = 12.dp)
            .size(32.dp)
            .background(tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Color.White,
        )
    }
}

/** 厂商品牌原色图标；资源已经包含适合圆形裁剪的背景与安全区。 */
@Composable
internal fun ProviderBrandIcon(
    sourceType: String,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(sourceType) ?: return
    ProviderBrandImage(logo = logo, modifier = modifier)
}

@Composable
private fun ProviderBrandImage(
    @DrawableRes logo: Int,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .padding(end = 12.dp)
            .size(32.dp)
            .clip(CircleShape),
    )
}

/** 已知厂商使用品牌图标，未知来源继续按协议类型使用通用图标。 */
@Composable
internal fun ProviderIcon(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(provider)
    if (logo != null) {
        ProviderBrandImage(logo = logo, modifier = modifier)
        return
    }

    when (provider) {
        is CustomProviderSetting -> ProviderRoundIcon(
            icon = LucideR.drawable.lucide_ic_server,
            tint = IconTintGreen,
            modifier = modifier,
        )
        else -> ProviderRoundIcon(
            icon = LucideR.drawable.lucide_ic_globe,
            tint = IconTintBlue,
            modifier = modifier,
        )
    }
}

@DrawableRes
internal fun providerBrandLogoRes(provider: ProviderSetting): Int? =
    providerBrandLogoRes(ProviderSourceRegistry.resolve(provider))

@DrawableRes
internal fun providerBrandLogoRes(sourceType: String): Int? = when (ProviderSourceRegistry.normalize(sourceType)) {
    ProviderSourceTypes.OPENAI -> R.drawable.provider_logo_openai
    ProviderSourceTypes.ANTHROPIC -> R.drawable.provider_logo_anthropic
    ProviderSourceTypes.BAILIAN -> R.drawable.provider_logo_bailian
    ProviderSourceTypes.DEEPSEEK -> R.drawable.provider_logo_deepseek
    ProviderSourceTypes.MOONSHOT -> R.drawable.provider_logo_kimi
    ProviderSourceTypes.MIMO -> R.drawable.provider_logo_mimo
    ProviderSourceTypes.MINIMAX -> R.drawable.provider_logo_minimax
    ProviderSourceTypes.STEPFUN -> R.drawable.provider_logo_stepfun
    ProviderSourceTypes.SILICONFLOW -> R.drawable.provider_logo_siliconflow
    ProviderSourceTypes.OPENROUTER -> R.drawable.provider_logo_openrouter
    else -> null
}

/** 分隔线缩进对齐圆形图标之后的文字起始位置：insideMargin(16) + 图标(32) + 间距(12) = 60dp。 */
@Composable
internal fun ProviderDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 60.dp))
}

internal enum class TagChipTone { Normal, Emphasized, Warning }

/** 小胶囊标签，用于能力标签与状态标记。 */
@Composable
internal fun TagChip(
    text: String,
    tone: TagChipTone = TagChipTone.Normal,
) {
    val background: Color
    val foreground: Color
    when (tone) {
        TagChipTone.Normal -> {
            background = MiuixTheme.colorScheme.secondaryContainer
            foreground = MiuixTheme.colorScheme.onSecondaryContainer
        }
        TagChipTone.Emphasized -> {
            background = MiuixTheme.colorScheme.primaryContainer
            foreground = MiuixTheme.colorScheme.onPrimaryContainer
        }
        TagChipTone.Warning -> {
            background = StatusWarning.copy(alpha = 0.15f)
            foreground = StatusWarning
        }
    }
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = foreground,
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
