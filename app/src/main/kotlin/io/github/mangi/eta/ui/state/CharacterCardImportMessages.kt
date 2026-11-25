package io.github.mangi.eta.ui.state

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings

/** 导入只公开受控分类，文件内容与底层异常文本不进入界面。 */
internal fun characterCardImportMessage(code: String?): String? = when (code) {
    "CARD_V3_INVALID" -> AppStrings.get(
        R.string.character_import_error_v3_invalid,
        "PNG 中优先使用的 V3 角色数据损坏，未回退到 V2。请重新下载原始角色卡。",
    )
    "CARD_METADATA_MISSING" -> AppStrings.get(
        R.string.character_import_error_metadata_missing,
        "这张 PNG 没有角色卡信息，请选择原始角色卡图片，而不是截图或压缩后的图片。",
    )
    "CARD_INVALID_JSON" -> AppStrings.get(
        R.string.character_import_error_invalid_json,
        "角色卡 JSON 格式无效，请检查文件是否完整。",
    )
    "CARD_TOO_LARGE" -> AppStrings.get(
        R.string.character_import_error_too_large,
        "角色卡超出大小限制，请简化设定或选择更小的文件。",
    )
    "CARD_INVALID_UTF8" -> AppStrings.get(
        R.string.character_import_error_invalid_utf8,
        "角色卡文本编码无效，需要 UTF-8 编码的 JSON 文件。",
    )
    "CARD_UNSUPPORTED_SPEC" -> AppStrings.get(
        R.string.character_import_error_unsupported_spec,
        "暂不支持这份角色卡声明的格式，请导出为 V2 或 V3 角色卡后重试。",
    )
    "CARD_INVALID_PNG" -> AppStrings.get(
        R.string.character_import_error_invalid_png,
        "PNG 文件损坏或结构不完整，请重新下载原始角色卡。",
    )
    "CARD_INVALID_DATA" -> AppStrings.get(
        R.string.character_import_error_invalid_data,
        "角色卡字段格式无效，请检查名称、文本、开场白及内嵌世界书数据。",
    )
    else -> null
}
