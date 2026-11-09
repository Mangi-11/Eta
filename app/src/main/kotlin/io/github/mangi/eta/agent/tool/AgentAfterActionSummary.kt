package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.Observation
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import io.github.mangi.eta.agent.device.ScreenObservationMetadata
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动作后观察的模型投影：先给结论（界面是否变化、是否切换应用或窗口），再给新的节点列表。
 * 变化判定只比较节点的可见语义（文本、描述、类名、view_id、位置），不依赖时间戳或动画帧。
 */
internal object AgentAfterActionSummary {
    fun build(before: ElementObservation?, observation: Observation): JSONObject {
        val metadata = JSONObject(observation.content)
        val after = observation.elementObservation
        val nodes = after?.nodes.orEmpty()
        val status = metadata.optString("ui_tree_status").ifBlank {
            when {
                after == null -> "query_failed"
                nodes.isEmpty() -> "empty"
                else -> "available"
            }
        }
        val changed = if (status == "available" && nodes.isNotEmpty()) {
            before == null || signature(before.nodes) != signature(nodes)
        } else {
            null
        }
        val note = if (changed == null) {
            metadata.optString("ui_tree_note").ifBlank {
                ScreenObservationMetadata.uiTreeNote(status, observation.image != null)
            }
        } else if (changed) {
            "以上为动作后的新界面，可直接用该 observation_id 继续操作；需要更多节点或截图时再调用 observe_screen"
        } else {
            "界面没有可见变化，动作可能未生效；请换目标或方式，不要原样重复"
        }
        return JSONObject()
            .put("observation_id", after?.id ?: JSONObject.NULL)
            .put("observation_source", after?.source?.wireName ?: metadata.opt("observation_source") ?: JSONObject.NULL)
            .put("package", after?.packageName ?: metadata.optJSONObject("focus")?.optString("package").orEmpty())
            .put("screen_changed", changed ?: JSONObject.NULL)
            .put(
                "package_changed",
                if (after != null) before != null && before.packageName != after.packageName else JSONObject.NULL,
            )
            .put(
                "window_changed",
                if (after != null) before != null && before.windowId != after.windowId else JSONObject.NULL,
            )
            .put("ui_tree_status", status)
            .put("ui_tree_truncated", after?.truncated ?: false)
            .put("ui_nodes", JSONArray().also { array -> nodes.forEach { array.put(it.compactJson()) } })
            .put("note", note)
            .apply {
                metadata.optJSONObject("focus")?.let { put("focus", it) }
                metadata.optString("code").takeIf(String::isNotBlank)?.let { put("code", it) }
            }
    }

    private fun signature(nodes: List<UiNode>): List<String> =
        nodes.map { "${it.className}|${it.viewId}|${it.text}|${it.desc}|${it.bounds.toShortString()}" }

    /** 精简字段：省略默认值为 false 的布尔与包名，降低每步附带观察的 token 开销。 */
    private fun UiNode.compactJson(): JSONObject = JSONObject()
        .put("index", index)
        .apply {
            if (text.isNotBlank()) put("text", text)
            if (desc.isNotBlank()) put("desc", desc)
            put("class", className.substringAfterLast('.'))
            if (viewId.isNotBlank()) put("view_id", viewId.substringAfterLast('/'))
            put("center", JSONObject().put("x", centerX).put("y", centerY))
            if (clickable) put("clickable", true)
            if (longClickable) put("long_clickable", true)
            if (scrollable) put("scrollable", true)
            if (editable) put("editable", true)
            if (focused) put("focused", true)
            if (password) put("password", true)
            if (!enabled) put("enabled", false)
            checked?.let { put("checked", it) }
            if (selected) put("selected", true)
            if (hint.isNotBlank()) put("hint", hint)
        }
}
