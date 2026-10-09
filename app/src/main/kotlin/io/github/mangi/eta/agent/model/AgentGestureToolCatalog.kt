package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 屏幕手势与节点交互工具 schema。 */
internal object AgentGestureToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "tap",
                    description = "点击坐标。能用节点时优先 tap_element；看截图定位时用 coordinate_space=normalized（0–999），坐标来自 ui_nodes 时用 screen。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_area",
                    description = "点击矩形区域中心。大按钮、大列表项和可见文字区域优先用这个工具；坐标系同 tap，必须显式填写 coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_element",
                    description = "点击指定观察快照中的 UI 节点。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Eta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press",
                    description = "长按坐标。能用节点时优先 long_press_element；坐标系同 tap，必须显式填写 coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "长按时长，300 到 3000，默认 800")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press_element",
                    description = "长按指定观察快照中的 UI 节点。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Eta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "长按时长，300 到 3000，默认 800")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "swipe",
                    description = "从一个坐标拖到另一个坐标（手指方向），用于拖动滑块、轮播、地图等精确手势。浏览列表请用 scroll。坐标系同 tap，必须显式填写 coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "滑动时长，100 到 2000，默认 500")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll",
                    description = "按内容浏览方向滚动当前屏幕的主列表：down 显示下方内容，up 显示上方内容，left/right 同理。结果里 moved=false 且 at_boundary=true 表示已到底，换方向或结束；页面有多个列表时用 scroll_element 指定目标。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                )
                                .put(
                                    "amount",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("page").put("small"))
                                        .put("description", "page 约翻一屏（默认），small 只露出少量新内容，用于把目标项微调进视野。"),
                                )
                        )
                        .put("required", JSONArray().put("direction"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll_element",
                    description = "按内容浏览方向滚动指定观察快照中的可滚动 UI 节点：down 显示下方内容，up 显示上方内容，left 显示左侧内容，right 显示右侧内容。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Eta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的可滚动 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                        .put("description", "内容浏览方向；down 显示下方内容，up 显示上方内容。")
                                )
                                .put(
                                    "amount",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("page").put("small"))
                                        .put("description", "page 约翻一屏（默认），small 只露出少量新内容，用于把目标项微调进视野。"),
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id").put("direction"))
                )
            )
    }
}
