package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentVirtualScreenToolCatalog {
    fun appendTo(tools: JSONArray) {
        fun number(min: Int, max: Int) =
            JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)
        tools.put(
            AgentToolSchema.function(
                "virtual_screen",
                "Experimental Root display for isolated Android app operations. When the user enables Virtual screen, normal launch_app, observe_screen, gesture, node and text tools automatically use this display and create it on demand; prefer those tools. Use this tool for explicit dimensions and lifecycle control. launch requires an exact package/activity component and rejects existing tasks on other displays. observe returns this display's image; tap/swipe use its pixel coordinates. Users can interact through the viewer. UI nodes and Unicode input require connected accessibility on this display. back and close apply only to this display. Failures remain on the virtual route unless the user has enabled fallback requests and explicitly allows this run through its notification. UI_DISPLAY_SWITCHED never replays the failed operation; use normal UI tools and obtain a fresh primary observation before interacting. allowScreenOff=true requires the user's separate screen-off setting. Does not unlock or power off the physical screen. Display and tasks are destroyed at run end or after 15 minutes. Secure app content may stay black.",
                JSONObject().put("type", "object").put("additionalProperties", false).put(
                    "properties", JSONObject()
                        .put(
                            "action",
                            JSONObject().put("type", "string").put(
                                "enum",
                                JSONArray(
                                    listOf(
                                        "create",
                                        "launch",
                                        "observe",
                                        "tap",
                                        "swipe",
                                        "back",
                                        "close"
                                    )
                                )
                            )
                        )
                        .put("width", number(320, 1080)).put("height", number(480, 1920))
                        .put("density", number(160, 480))
                        .put("allowScreenOff", JSONObject().put("type", "boolean"))
                        .put("component", JSONObject().put("type", "string").put("maxLength", 300))
                        .put("x", number(0, 1079)).put("y", number(0, 1919))
                        .put("x1", number(0, 1079)).put("y1", number(0, 1919))
                        .put("x2", number(0, 1079)).put("y2", number(0, 1919))
                        .put("durationMs", number(100, 2000))
                )
                    .put("required", JSONArray().put("action"))
            )
        )
    }
}
