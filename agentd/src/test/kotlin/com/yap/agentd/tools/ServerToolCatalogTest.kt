package com.yap.agentd.tools

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端工具表与提示词的一致性（本轮硬要求：「你能做什么」必须与实际工具表一致）。
 *
 * 钉住四件事：
 * 1. 注册的工具就是模型能看到的工具（`toOpenAiToolsJson` 的名字集合 = [ServerToolCatalog.names]）；
 * 2. 提示词段里每个工具都出现，且没有「提示词说有、表里没有」的工具（反向也查）；
 * 3. 每个工具的 `parameters` 是合法 JSON Schema（`type=object`、`required` 指向真实属性）；
 * 4. 没联网时 `web_search` / `web_fetch` 从提示词里消失，并补一句「不能联网」。
 */
class ServerToolCatalogTest {

    @Test
    fun `catalog is the single source of names for tools json`() {
        val tools = Json.parse(ServerToolCatalog.toOpenAiToolsJson()) as JsonValue.Arr
        val names = tools.items.map { item ->
            val function = (item as JsonValue.Obj).fields.getValue("function") as JsonValue.Obj
            ((function.fields.getValue("name") as JsonValue.Str).value)
        }
        assertEquals(ServerToolCatalog.names, names)
        assertEquals("0.16 把 think 也注册进来之后是 18 个工具", 18, names.size)
        assertTrue(names.containsAll(listOf(
            // 0.16：think 不再是「客户端的提示词技巧」，它在这一轮把 reasoning_effort 升到 high。
            "think",
            "stay_silent", "defer_reply", "set_mood", "set_activity", "diary",
            "remember", "recall", "memorize", "web_search", "web_fetch", "sticker",
            "send_message", "block_user",
            // 0.14.1：四个「以前服务端做不到」的工具现在真的注册了（提示词不再落后于实现）。
            "update_persona", "set_quiet_hours", "listen", "watch"
        )))
    }

    @Test
    fun `every registered tool has a schema whose required fields exist`() {
        for (spec in ServerToolCatalog.all) {
            val parameters = Json.parse(spec.parametersJson) as JsonValue.Obj
            assertEquals("${spec.name} 的 schema 必须是 object", "object", (parameters.fields["type"] as JsonValue.Str).value)
            val properties = (parameters.fields["properties"] as? JsonValue.Obj)?.fields?.keys.orEmpty()
            val required = (parameters.fields["required"] as? JsonValue.Arr)?.items
                ?.map { (it as JsonValue.Str).value }
                .orEmpty()
            for (field in required) {
                assertTrue("${spec.name} 的 required 字段 $field 不在 properties 里", field in properties)
            }
            assertTrue("${spec.name} 要有中文说明", spec.description.length > 10)
        }
    }

    @Test
    fun `capability prompt lists exactly the registered tools and their contrasts`() {
        val prompt = ServerToolCatalog.capabilityPrompt()
        assertTrue(prompt.startsWith("# 你能做什么"))
        for (spec in ServerToolCatalog.all) {
            assertTrue("提示词缺少 ${spec.name}", prompt.contains("`${spec.name}`"))
        }
        // 反向：提示词里出现的工具名都必须在表里（用反引号扫一遍）。
        val mentioned = Regex("`([a-z_]+)`").findAll(prompt).map { it.groupValues[1] }.toSet()
        assertEquals(ServerToolCatalog.names.toSet(), mentioned)
        // 最容易混淆的一对必须在提示词里说清楚。
        assertTrue(prompt.contains("defer_reply 是「过一会儿再回」"))
        assertTrue(prompt.contains("不是关于对方的记忆"))
    }

    @Test
    fun `web tools disappear from the prompt when networking is off`() {
        val prompt = ServerToolCatalog.capabilityPrompt(webEnabled = false)
        assertFalse(prompt.contains("`web_search`"))
        assertFalse(prompt.contains("`web_fetch`"))
        assertTrue("要诚实说明不能联网", prompt.contains("你现在没有联网能力"))
        // 其它工具不受影响。
        assertTrue(prompt.contains("`diary`"))
        assertTrue(prompt.contains("`stay_silent`"))
    }

    @Test
    fun `byName is trimmed and unknown tools return null`() {
        assertNotNull(ServerToolCatalog.byName(" set_mood "))
        assertNotNull(ServerToolCatalog.byName(" update_persona "))
        assertNotNull("0.16 起 think 由服务端注册（思考强度开关）", ServerToolCatalog.byName("think"))
        assertEquals(null, ServerToolCatalog.byName("block"))
    }

    @Test
    fun `catalog advertises exactly what the server can now execute`() {
        // 0.14.1：`update_persona` / `set_quiet_hours` / `listen` / `watch` / `sticker.make` 都有实现。
        for (name in listOf("listen", "watch", "update_persona", "set_quiet_hours")) {
            assertTrue("服务端应该注册 $name", ServerToolCatalog.names.contains(name))
        }
        // 0.16：`think` 真的由服务端执行——它不是写文案，而是把这一轮后续请求的
        // `reasoning_effort` 从 low 升到 high（见 `ToolExecutor.think` / `LlmReplyGenerator`）。
        assertTrue("0.16 起服务端注册 think", ServerToolCatalog.names.contains("think"))
        // sticker 的 action 现在包含 make（服务端真的会合成 GIF）。
        val sticker = ServerToolCatalog.byName("sticker")!!
        assertTrue(sticker.description.contains("现做"))
        XCTAssertSchemaEnum(sticker.parametersJson, "action", listOf("send", "list", "make"))
        // listen/watch 的参数是可选的音频/视频 id。
        assertTrue(ServerToolCatalog.byName("listen")!!.parametersJson.contains("audioId"))
        assertTrue(ServerToolCatalog.byName("watch")!!.parametersJson.contains("videoId"))
    }

    private fun XCTAssertSchemaEnum(parametersJson: String, field: String, expected: List<String>) {
        val parameters = Json.parse(parametersJson) as JsonValue.Obj
        val properties = parameters.fields.getValue("properties") as JsonValue.Obj
        val property = properties.fields.getValue(field) as JsonValue.Obj
        val enumValues = (property.fields["enum"] as JsonValue.Arr).items.map { (it as JsonValue.Str).value }
        assertEquals(expected, enumValues)
    }
}
