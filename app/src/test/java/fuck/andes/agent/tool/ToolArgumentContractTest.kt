package fuck.andes.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolArgumentContractTest {
    @Test
    fun `missing coordinates cannot become a zero coordinate gesture`() {
        assertEquals("x", ToolArgumentContract.validate("tap", JSONObject())?.field)
        assertEquals(
            "y2",
            ToolArgumentContract.validate(
                "swipe",
                JSONObject("""{"x1":1,"y1":2,"x2":3}"""),
            )?.field,
        )
    }

    @Test
    fun `missing text cannot silently clear text or clipboard`() {
        assertEquals(
            "text",
            ToolArgumentContract.validate("replace_text", JSONObject())?.field,
        )
        assertEquals(
            "text",
            ToolArgumentContract.validate("input_text", JSONObject())?.field,
        )
        assertEquals(
            "text",
            ToolArgumentContract.validate("set_clipboard", JSONObject())?.field,
        )
    }

    @Test
    fun `wrong json types are rejected before a side effect`() {
        assertEquals(
            "x",
            ToolArgumentContract.validate(
                "tap",
                JSONObject("""{"x":"100","y":200}"""),
            )?.field,
        )
        assertEquals(
            "text",
            ToolArgumentContract.validate(
                "paste_text",
                JSONObject("""{"text":123}"""),
            )?.field,
        )
        assertEquals(
            "x",
            ToolArgumentContract.validate(
                "tap",
                JSONObject("""{"x":-1,"y":200}"""),
            )?.field,
        )
    }

    @Test
    fun `indexed text edit requires matching observation reference`() {
        assertEquals(
            "observation_id",
            ToolArgumentContract.validate(
                "replace_text",
                JSONObject("""{"text":"new","index":4}"""),
            )?.field,
        )
        assertEquals(
            "index",
            ToolArgumentContract.validate(
                "input_text",
                JSONObject("""{"text":"new","index":4,"observation_id":"o1"}"""),
            )?.field,
        )
    }

    @Test
    fun `valid destructive argument including explicit empty text is accepted`() {
        assertNull(
            ToolArgumentContract.validate(
                "replace_text",
                JSONObject("""{"text":"","index":4,"observation_id":"o1"}"""),
            ),
        )
        assertNull(
            ToolArgumentContract.validate(
                "tap",
                JSONObject("""{"x":100,"y":200,"coordinate_space":"screen"}"""),
            ),
        )
    }

    @Test
    fun `empty paste is rejected without touching clipboard`() {
        assertEquals(
            "text",
            ToolArgumentContract.validate(
                "paste_text",
                JSONObject("""{"text":""}"""),
            )?.field,
        )
    }
}
