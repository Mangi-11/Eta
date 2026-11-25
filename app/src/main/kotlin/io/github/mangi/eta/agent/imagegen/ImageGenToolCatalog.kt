package io.github.mangi.eta.agent.imagegen

import io.github.mangi.eta.agent.model.catalog.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 生图与图像编辑统一工具 Schema。
 *
 * 仅在用户在「设置 → AI 生图」启用后注入模型工具列表，避免未配置时占用上下文 Token。
 */
internal object ImageGenToolCatalog {
    const val TOOL_NAME = "generate_image"

    val names: Set<String> = setOf(TOOL_NAME)

    fun appendTo(target: JSONArray) {
        val properties = JSONObject()
            .put(
                "prompt",
                JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "详细的图像生成或编辑提示词，描述主体、构图、风格、光影、氛围以及画面中需要精确渲染的文字内容。",
                    ),
            )
            .put(
                "aspect_ratio",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("auto", "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3")))
                    .put("description", "画面宽高比；未明确指定时传 auto 遵循用户默认设置。"),
            )
            .put(
                "resolution",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("1K", "2K", "4K")))
                    .put("description", "清晰度档位（可选）；未明确要求高分辨率时省略以遵循用户默认配置。"),
            )
            .put(
                "count",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", 1)
                    .put("maximum", 4)
                    .put("description", "生成张数，默认 1，最多 4。"),
            )
            .put(
                "reference_images",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string"))
                    .put(
                        "description",
                        "用于图生图、局部编辑或风格参考的图片列表（可选）。可传 \"latest_user_image\"（引用用户最近发送的图片）、\"latest_generated_image\"（引用本会话最近一次生成的图片）、已生成图片 ID（img_...）或本地图片绝对路径。",
                    ),
            )
        target.put(
            AgentToolSchema.function(
                name = TOOL_NAME,
                description = "使用用户已配置的 AI 生图模型根据提示词生成新图片，或基于参考图进行图像编辑与风格迁移。生成的图片会直接展示在聊天界面中，无需在回复中伪造图片链接。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray(listOf("prompt"))),
            ),
        )
    }
}
