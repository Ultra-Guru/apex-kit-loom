package com.apex.loomkit.weave

import com.apex.loomkit.weave.loop.LoomEnvironmentInfo
import com.apex.loomkit.weave.loop.LoomHeuristicEstimator
import com.apex.loomkit.weave.loop.LoomLogger
import com.apex.loomkit.weave.loop.LoomMemory
import com.apex.loomkit.weave.loop.LoomSession
import com.apex.loomkit.weave.loop.LoomWorkspaceContextSource
import com.apex.loomkit.weave.markup.LoomMarkupGrammar
import com.apex.loomkit.weave.WeaveMode
import com.apex.loomkit.weave.WeaveFileRef
import com.apex.loomkit.weave.WeaveUserInput
import com.apex.loomkit.weave.WeaveMessage
import com.apex.loomkit.weave.WeaveToolDefinition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomSession 会话状态与提示词装配测试：
 *
 * - 轮次追加样式（用户输入 / 助手回合 in-band / 附件标签 / 空轮不落账）；
 * - 系统提示词段落装配（环境 / 工具目录 / PLAN 布样段 / 人设 / 附加段）；
 * - 记忆通道联动（append 落账 / bindWorkspace 重放 / replaceHistory 持久化）。
 */
class LoomSessionTest {

    /** 记录型假记忆（append 序列 / save 快照）。 */
    private class RecordingMemory : LoomMemory {
        val appended = mutableListOf<WeaveMessage>()
        private var saved: List<WeaveMessage> = emptyList()

        override fun load(): List<WeaveMessage> = saved
        override fun append(message: WeaveMessage) {
            appended.add(message)
        }

        override fun save(messages: List<WeaveMessage>) {
            saved = messages
        }

        override fun clear() {
            saved = emptyList()
        }

        override fun count(): Int = saved.size
        override fun bindWorkspace(workspaceId: String) = Unit
    }

    /** 只读回放型假记忆（含脏 system 轮）。 */
    private class ReplayMemory(private val stored0: List<WeaveMessage>) : LoomMemory {
        private var stored = stored0

        override fun load(): List<WeaveMessage> = stored
        override fun append(message: WeaveMessage) {
            stored = stored + message
        }

        override fun save(messages: List<WeaveMessage>) {
            stored = messages
        }

        override fun clear() {
            stored = emptyList()
        }

        override fun count(): Int = stored.size
        override fun bindWorkspace(workspaceId: String) = Unit
    }

    private val logger = object : LoomLogger {
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private fun newSession(
        memory: LoomMemory?,
        context: LoomWorkspaceContextSource? = null
    ): LoomSession = LoomSession(
        config = LoomEngineConfig(nativeToolCalling = true),
        memory = memory,
        estimator = LoomHeuristicEstimator,
        environmentSource = { LoomEnvironmentInfo(platformText = "Android 测试机") },
        workspaceContextSource = context,
        modelInfoSource = null,
        logger = logger
    )

    @Test
    fun `user input turn carries text and attachment tags`() {
        val s = newSession(null)
        s.appendUserInput(
            WeaveUserInput(
                text = "读一下这个文件",
                files = listOf(
                    WeaveFileRef(
                        name = "a.kt",
                        mimeType = "text/kotlin",
                        localPath = "/tmp/a.kt",
                        sizeBytes = 123
                    )
                )
            ),
            steeringNotes = emptyList()
        )
        val turn = s.historySnapshot().single() as WeaveMessage.User
        assertTrue(turn.content.contains("读一下这个文件"))
        assertTrue(turn.content.contains("[附件] a.kt"))
        assertTrue(turn.content.contains("123 字节"))
    }

    @Test
    fun `steering notes prepend user turn`() {
        val s = newSession(null)
        s.appendUserInput(
            WeaveUserInput.text("继续"),
            steeringNotes = listOf("[转向] 补充：改用方案B")
        )
        val turn = s.historySnapshot().single() as WeaveMessage.User
        assertTrue(turn.content.startsWith("[转向]"))
        assertTrue(turn.content.endsWith("继续"))
    }

    @Test
    fun `assistant round is fully in-band`() {
        val s = newSession(null)
        s.appendAssistantRound(
            deliberation = "先看文件",
            text = "我先读取文件。",
            invocations = listOf(
                LoomInvocation(
                    name = "CodeRead",
                    args = listOf(LoomArg("path", "a.kt"))
                )
            )
        )
        val turn = s.historySnapshot().single() as WeaveMessage.Assistant
        assertTrue(turn.content.contains("<deliberate>先看文件</deliberate>"))
        assertTrue(turn.content.contains("我先读取文件。"))
        assertTrue(turn.content.contains("<use name=\"CodeRead\">"))
        assertTrue(turn.content.contains("<arg name=\"path\">a.kt</arg>"))
        assertTrue(turn.toolCalls.isEmpty()) // 零原生 toolCalls——全 in-band
    }

    @Test
    fun `blank assistant round is not recorded`() {
        val s = newSession(null)
        s.appendAssistantRound(deliberation = "", text = "", invocations = emptyList())
        assertTrue(s.historySnapshot().isEmpty())
    }

    @Test
    fun `system prompt assembles sections in order`() {
        val s = newSession(
            null,
            context = object : LoomWorkspaceContextSource {
                override fun workspaceSection(): String = "工作区段：demo"
                override fun globalRulesSection(): String = "全局规则段"
                override fun projectRulesSection(): String? = null
            }
        )
        s.updateRolePersona("Kotlin 专家", "关注性能")
        s.updateSessionExtras("附加上下文XYZ")
        val prompt = s.assembleSystemPrompt(
            listOf(WeaveToolDefinition(name = "Echo", description = "回声", parameters = "{}"))
        )
        val identity = prompt.indexOf("织造")
        val workspace = prompt.indexOf("工作区段：demo")
        val rules = prompt.indexOf("全局规则段")
        val persona = prompt.indexOf("Kotlin 专家")
        val protocol = prompt.indexOf("工具调用协议")
        val catalog = prompt.indexOf("Echo")
        val extras = prompt.indexOf("附加上下文XYZ")
        assertTrue(identity >= 0)
        assertTrue(identity < workspace)
        assertTrue(workspace < rules)
        assertTrue(rules < persona)
        assertTrue(persona < protocol)
        assertTrue(protocol < catalog)
        assertTrue(catalog < extras)
    }

    @Test
    fun `plan mode adds plan protocol section`() {
        val s = newSession(null)
        val plain = s.assembleSystemPrompt(emptyList())
        assertFalse(plain.contains("loom-plan"))
        s.updateMode(WeaveMode.PLAN)
        val planned = s.assembleSystemPrompt(emptyList())
        assertTrue(planned.contains("loom-plan"))
        assertTrue(planned.contains(LoomMarkupGrammar.TAG_PLAN))
    }

    @Test
    fun `memory append is called for each turn`() {
        val mem = RecordingMemory()
        val s = newSession(mem)
        s.appendUserInput(WeaveUserInput.text("你好"), steeringNotes = emptyList())
        s.appendUserTurn("回执轮")
        assertEquals(2, mem.appended.size)
        assertEquals(2, s.historyCount())
    }

    @Test
    fun `bind workspace reloads persisted history and drops system turns`() {
        val mem = ReplayMemory(
            listOf(
                WeaveMessage.System("旧系统轮（脏数据）"),
                WeaveMessage.User("旧问题"),
                WeaveMessage.Assistant("旧回答")
            )
        )
        val s = newSession(mem)
        s.bindWorkspace("ws1", "演示", File("/tmp/ws1"), null)
        assertEquals(2, s.historyCount())
        assertFalse(s.historySnapshot().any { it is WeaveMessage.System })
    }

    @Test
    fun `replace history persists`() {
        val mem = RecordingMemory()
        val s = newSession(mem)
        s.appendUserInput(WeaveUserInput.text("a"), steeringNotes = emptyList())
        s.replaceHistory(listOf(WeaveMessage.User("折叠后")))
        assertEquals(listOf<WeaveMessage>(WeaveMessage.User("折叠后")), s.historySnapshot())
    }
}
