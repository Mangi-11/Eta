package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentToolSchema {
    fun coordinateSpace(): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("normalized").put("screen").put("screenshot"))
            .put(
                "description",
                "必填。normalized（推荐）：x、y 取 0–999，相对整块屏幕的比例，看截图时用它，不受图片缩放影响；" +
                    "screen：真实屏幕像素，ui_nodes 的 center/bounds 就是这个坐标；" +
                    "screenshot：最近一次附图的原始像素，仅当你确定看到的是未缩放原图时使用。",
            )

    fun function(
        name: String,
        description: String,
        parameters: JSONObject,
    ): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
}
