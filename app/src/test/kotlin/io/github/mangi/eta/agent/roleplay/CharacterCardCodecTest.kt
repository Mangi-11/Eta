package io.github.mangi.eta.agent.roleplay

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardCodecTest {
    @Test
    fun legacyCardIsUpgradedAndUnknownValuesSurviveEditingAndPngRoundTrip() {
        val card = CharacterCardCodec.decodeJson("""{
          "name":"旧名","description":"原设定","vendor":{"nested":[1,true,null]},
          "extensions":{"foreign":{"values":["a",{"x":2}]}}
        }""")
        val edited = card.withEdits(name = "新名", description = "新设定")
        val png = CharacterCardPng.write(avatar(), edited)
        val decoded = CharacterCardPng.read(png)
        assertEquals("新名", decoded.name)
        assertEquals("新设定", decoded.description)
        assertEquals(card.raw["vendor"], decoded.raw["vendor"])
        assertEquals(card.extensions, decoded.extensions)
        assertEquals("chara_card_v3", decoded.raw.text("spec"))
        assertTrue(png.toString(Charsets.ISO_8859_1).contains("chara"))
        assertTrue(png.toString(Charsets.ISO_8859_1).contains("ccv3"))
        val second = CharacterCardPng.read(CharacterCardPng.write(png, decoded.withEdits(name = "再编辑")))
        assertEquals("再编辑", second.name)
    }

    @Test
    fun versionedDataWinsOverStaleLegacyMirrors() {
        val card = CharacterCardCodec.decodeJson("""{
          "spec":"chara_card_v3","spec_version":"3.0","name":"过时镜像",
          "data":{"name":"当前名称","nickname":"简称","assets":[{"uri":"x","type":"other"}],"extensions":{}}
        }""")
        assertEquals("当前名称", card.name)
        val roundTrip = CharacterCardCodec.decodeJson(CharacterCardCodec.exportView(card, 3))
        assertEquals(card.data["assets"], roundTrip.data["assets"])
        assertEquals("当前名称", roundTrip.raw.text("name"))
        assertEquals("简称", roundTrip.nickname)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ordinaryPngIsNotSilentlyImportedAsCharacter() { CharacterCardPng.read(avatar()) }

    @Test(expected = IllegalArgumentException::class)
    fun corruptPngIsRejected() {
        val bytes = CharacterCardPng.write(avatar(), CharacterCardCodec.create("角色"))
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        CharacterCardPng.read(bytes)
    }

    @Test
    fun macrosExpandKnownFieldsWithoutExecutingUnknownVariables() {
        val card = CharacterCardCodec.create("林").withEdits(description = "{{char}}住在山间。")
        val result = CharacterMacros.expand("{{description}} <USER> {{original}} {{setvar::x::1}}", card, "小安", original = "原指令")
        assertEquals("林住在山间。 小安 原指令 {{setvar::x::1}}", result)
        assertEquals("{{description}}", CharacterMacros.expand("{{description}}", card.withEdits(description = "{{description}}")))
        assertFalse(CharacterMacros.expand("{{//注释}}你好", card).contains("注释"))
    }

    @Test
    fun editingWorldbookPreservesForeignBookAndEntryExtensions() {
        val card = CharacterCardCodec.decodeJson("""{"name":"角色","character_book":{
          "name":"世界","vendor":"保留","extensions":{"vendor":{"x":[1,2]}},
          "entries":[{"id":7,"keys":["山"],"content":"旧正文","enabled":true,"insertion_order":3,
            "extensions":{"position":0,"unknown":["a",{"b":true}]}}]
        }}""")
        val draft = card.worldbookDraft()
        val updated = card.withWorldbook(draft.copy(name = "新世界", scanDepth = 4,
            entries = draft.entries.map { it.copy(content = "新正文", position = "after_char") },
        ))
        val result = CharacterCardCodec.decodeJson(CharacterCardCodec.encodeJson(updated)).worldbookDraft()
        assertEquals("新正文", result.entries.single().content)
        assertEquals("after_char", result.entries.single().position)
        assertEquals(draft.raw["vendor"], result.raw["vendor"])
        assertEquals(draft.raw["extensions"], result.raw["extensions"])
        assertEquals(draft.entries.single().raw["id"], result.entries.single().raw["id"])
        assertEquals(4, result.scanDepth)
    }

    @Test
    fun compatibilityWarningsShareMacroAndWorldbookSupportWithoutEchoingCardText() {
        val card = CharacterCardCodec.decodeJson("""{"name":"角色",
          "description":"{{setvar::private_key::private_value}} <script>private_script()</script>",
          "extensions":{"regex_scripts":[{"x":"private_regex"}],"scripts":["private_script"],
            "depth_prompt":{"prompt":"备注","depth":"invalid"}},
          "character_book":{"entries":[{"enabled":true,"constant":true,"content":"正文","extensions":{"group":"private_group"}}]}
        }""")
        val warnings = CharacterCardCompatibility.warnings(card)
        assertTrue(warnings.any { it.contains("未支持的宏") })
        assertTrue(warnings.any { it.contains("HTML") })
        assertTrue(warnings.any { it.contains("正则替换") })
        assertTrue(warnings.any { it.contains("1 条世界书") })
        assertTrue(warnings.any { it.contains("深度备注") })
        assertFalse(warnings.any { it.contains("private_") })
        assertEquals(null, card.depthPrompt)
        assertEquals(emptyList<String>(), CharacterCardCompatibility.warnings(CharacterCardCodec.create("角色")
            .withEdits(description = "{{char}}与{{user}}交流。<START> {{original}}")))
    }

    @Test
    fun unrelatedWorldbookEditPreservesUnsupportedPosition() {
        val card = CharacterCardCodec.decodeJson("""{"name":"角色","character_book":{"entries":[
          {"enabled":true,"constant":true,"content":"正文","position":"at_depth","extensions":{"position":4,"depth":2}}
        ]}}""")
        val updated = card.withWorldbook(card.worldbookDraft().copy(name = "新书名"))
        assertEquals("at_depth", updated.worldbookDraft().entries.single().position)
        assertEquals(1, CharacterWorldbook.unsupportedEntries(updated).size)
    }

    @Test
    fun invalidJsonAndMissingMetadataHaveStableErrorCodes() {
        val failures = listOf(
            "CARD_INVALID_JSON" to { CharacterCardCodec.decodeJson("not-json") },
            "CARD_METADATA_MISSING" to { CharacterCardPng.read(avatar()) },
        )
        failures.forEach { (expected, operation) ->
            try {
                operation()
                throw AssertionError("Expected character card failure")
            } catch (failure: CharacterCardException) {
                assertEquals(expected, failure.code)
            }
        }
    }

    @Test
    fun changingOneVersionThreeFieldDoesNotMaterializeOtherMissingFields() {
        val card = CharacterCardCodec.decodeJson("""{"spec":"chara_card_v3","spec_version":"3.0",
          "foreign":{"x":1},"data":{"name":"原名","description":"描述","extensions":{"vendor":[1,true]}}}""")
        val updated = card.withEdits(name = "新名")
        val restored = CharacterCardCodec.decodeJson(CharacterCardCodec.encodeJson(updated))
        assertEquals(card.data.filterKeys { it != "name" }, restored.data.filterKeys { it != "name" })
        assertEquals(card.data.keys, restored.data.keys)
        assertEquals(card.raw["foreign"], restored.raw["foreign"])
        assertEquals("新名", restored.name)
        assertFalse(restored.data.containsKey("alternate_greetings"))
        assertFalse(restored.data.containsKey("creator_notes"))
    }

    @Test
    fun amplifiedCrossFieldMacrosFailBeforeBuildingUnboundedOutput() {
        val card = CharacterCardCodec.create("角色").withEdits(
            description = "{{scenario}}".repeat(100),
            scenario = "{{personality}}".repeat(100),
            personality = "设定".repeat(200),
        )
        try {
            CharacterMacros.expand("{{description}}", card)
            throw AssertionError("Expected bounded expansion failure")
        } catch (failure: CharacterCardException) {
            assertEquals("CARD_MACRO_EXPANSION_LIMIT", failure.code)
        }
    }

    private fun avatar() = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMwdgn9DwADRgHM73L1cQAAAABJRU5ErkJggg==",
    )
}
