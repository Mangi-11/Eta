package io.github.mangi.eta.ui.app

/** 导入只公开受控分类，文件内容与底层异常文本不进入界面。 */
internal fun characterCardImportMessage(code: String?): String? = when (code) {
    "CARD_V3_INVALID" -> "PNG 中优先使用的 V3 角色数据损坏，未回退到 V2。请重新下载原始角色卡。"
    "CARD_METADATA_MISSING" -> "这张 PNG 没有角色卡信息，请选择原始角色卡图片，而不是截图或压缩后的图片。"
    "CARD_INVALID_JSON" -> "角色卡 JSON 格式无效，请检查文件是否完整。"
    "CARD_TOO_LARGE" -> "角色卡超出大小限制，请简化设定或选择更小的文件。"
    "CARD_INVALID_UTF8" -> "角色卡文本编码无效，需要 UTF-8 编码的 JSON 文件。"
    "CARD_UNSUPPORTED_SPEC" -> "暂不支持这份角色卡声明的格式，请导出为 V2 或 V3 角色卡后重试。"
    "CARD_INVALID_PNG" -> "PNG 文件损坏或结构不完整，请重新下载原始角色卡。"
    "CARD_INVALID_DATA" -> "角色卡字段格式无效，请检查名称、文本、开场白及内嵌世界书数据。"
    else -> null
}
