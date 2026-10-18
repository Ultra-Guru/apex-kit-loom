package com.apex.loomkit.weave

import com.apex.loomkit.weave.loop.LoomLogger
import com.apex.loomkit.weave.loop.LoomMemory
import com.apex.loomkit.weave.loop.LoomModelClient
import com.apex.loomkit.weave.loop.LoomToolRunner
import com.apex.loomkit.weave.loop.LoomToolSource
import com.apex.loomkit.weave.packager.LoomPackageCatalog
import com.apex.loomkit.weave.packager.LoomPackageInfo
import com.apex.loomkit.weave.permission.LoomPermissionSnapshot
import com.apex.loomkit.weave.permission.LoomPermissionSource
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomEngine 门面测试（全端口 fake——验证组装与门面契约）：
 *
 * - execute 全链路（模型请求含本地工具面 / 织造包段注入提示词）；
 * - forcedTools 过滤（选中面外工具不可见）；
 * - 门面直通（档位/历史/工作区/清空联动/记忆通道）。
 */
class LoomEngineTest {

    /** 剧本式模型客户端（记录请求 + 出队回合文本）。 */
    private class ScriptedClient : LoomModelClient {
        val seenTools = mutableListOf<List<WeaveToolDefinition>>()
        val seenMessages = mutableListOf<List<WeaveMessage>>()
        val rounds = ArrayDeque<String>()

        override fun chatStream(
            contextTag: String,
            messages: List<WeaveMessage>,
            tools: List<WeaveToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<WeaveStreamChunk> = flow {
            seenTools.add(tools)
            seenMessages.add(messages)
            emit(WeaveStreamChunk(content = rounds.removeFirst()))
            emit(WeaveStreamChunk(isFinish = true, finishReason = "stop"))
        }

        override suspend fun chat(
            contextTag: String,
            messages: List<WeaveMessage>,
            temperature: Float,
            maxTokens: Int
        ): WeaveModelResponse = WeaveModelResponse(content = "摘要")
    }

    /** 记录型工具执行端口（执行序列 + 固定成功输出）。 */
    private class RecordingRunner : LoomToolRunner {
        val executed = mutableListOf<Pair<String, String>>()

        override fun executeStream(toolId: String, arguments: String): Flow<WeaveToolStream> =
            flow {
                executed.add(toolId to arguments)
                emit(WeaveToolStream.Complete("[$toolId] ok"))
            }
    }

    /** 记录型记忆端口。 */
    private class RecordingMemory : LoomMemory {
        val stored = mutableListOf<WeaveMessage>()
        override fun load(): List<WeaveMessage> = stored.toList()
        override fun append(message: WeaveMessage) {
            stored.add(message)
        }

        override fun save(messages: List<WeaveMessage>) {
            stored.clear()
            stored.addAll(messages)
        }

        override fun clear() = stored.clear()
        override fun count(): Int = stored.size
        override fun bindWorkspace(workspaceId: String) = Unit
    }

    private fun engine(
        runtime: ScriptedClient,
        runner: RecordingRunner = RecordingRunner(),
        memory: RecordingMemory? = null
    ): LoomEngine = LoomEngine(
        modelClient = runtime,
        hostToolSource = LoomToolSource {
            listOf(
                WeaveToolDefinition("CodeRead", "读文件", "{\"type\":\"object\"}"),
                WeaveToolDefinition("CodeWrite", "写文件", "{\"type\":\"object\"}")
            )
        },
        hostToolRunner = runner,
        memory = memory,
        permissionSource = LoomPermissionSource { LoomPermissionSnapshot() },
        packageCatalog = object : LoomPackageCatalog {
            override fun list() = listOf(
                LoomPackageInfo("kt-review", "Kotlin 审查", "四步审查法")
            )

            override fun load(id: String): LoomActivePackage? =
                if (id == "kt-review") {
                    LoomActivePackage(
                        id = "kt-review",
                        title = "Kotlin 审查",
                        instructions = "四步：读 / 查 / 修 / 验。"
                    )
                } else null
        },
        logger = LoomLogger.Silent
    )

    @Test
    fun `execute exposes local tools and completes`() = runTest {
        val runtime = ScriptedClient()
        runtime.rounds.addLast("直接回答。")
        val e = engine(runtime)
        val events = mutableListOf<WeaveEvent>()
        e.execute(WeaveUserInput.text("你好")).toList(events)
        assertTrue(events.any { it is WeaveEvent.ResponseComplete })
        // 模型请求面 = 宿主目录 2 + 本地 8（子线程5 + 织造包3）
        val tools = runtime.seenTools.single()
        assertEquals(2 + 8, tools.size)
        assertTrue(tools.any { it.name == "loom_thread_start" })
        assertTrue(tools.any { it.name == "loom_package_use" })
        // 系统提示词含协议与目录（段落次序断言由 SessionTest 覆盖——此处不重复）
        val system = runtime.seenMessages.single().first() as WeaveMessage.System
        assertTrue(system.content.contains("工具调用协议"))
        assertTrue(system.content.contains("CodeRead"))
    }

    @Test
    fun `package activation rides outcome turn and prompt section`() = runTest {
        val runtime = ScriptedClient()
        runtime.rounds.addLast(
            "激活审查包。<use name=\"loom_package_use\"><arg name=\"id\">kt-review</arg></use>"
        )
        runtime.rounds.addLast("已按包指引完成审查。")
        val e = engine(runtime)
        val events = mutableListOf<WeaveEvent>()
        e.execute(WeaveUserInput.text("审查这段代码")).toList(events)
        // 第二轮系统提示词含织造包段（本地工具执行后激活集合同步进会话）
        val secondSystem = runtime.seenMessages[1].first() as WeaveMessage.System
        assertTrue(secondSystem.content.contains("Kotlin 审查"))
        assertTrue(secondSystem.content.contains("四步：读 / 查 / 修 / 验"))
        // 回执轮注入了包指引
        assertTrue(
            events.filterIsInstance<WeaveEvent.ToolCallComplete>()
                .single().output.contains("四步：读 / 查 / 修 / 验")
        )
    }

    @Test
    fun `forced tools narrow the surface`() = runTest {
        val runtime = ScriptedClient()
        runtime.rounds.addLast("只看选中面。")
        val e = engine(runtime)
        e.updateForcedTools(setOf("CodeRead", "loom_thread_start"), exposeAll = false)
        val events = mutableListOf<WeaveEvent>()
        e.execute(WeaveUserInput.text("任务")).toList(events)
        val tools = runtime.seenTools.single().map { it.name }.toSet()
        assertEquals(setOf("CodeRead", "loom_thread_start"), tools)
    }

    @Test
    fun `facade passthroughs work`() = runTest {
        val runtime = ScriptedClient()
        val e = engine(runtime)
        e.setActiveWorkspace("ws1", "演示", File("/tmp/ws1"), "a.kt")
        assertEquals("ws1", e.currentWorkspace()?.id)
        assertEquals("演示", e.currentWorkspace()?.name)
        e.updateThinkingLevel(WeaveThinkingLevel.DEEP)
        assertEquals(WeaveThinkingLevel.DEEP, e.thinkingLevel())
        assertEquals(null, e.currentThinkingDecision())
        assertTrue(e.maxContextTokens() >= 8_000)
        e.updateGlobalRules("规则A")
        e.updateSessionExtras("附加")
        e.updateRolePersona("专家", "提示")
        e.clearConversation()
        assertEquals(0, e.historyCount())
    }

    @Test
    fun `host tools execute through routing runner`() = runTest {
        val runtime = ScriptedClient()
        val runner = RecordingRunner()
        runtime.rounds.addLast(
            "<use name=\"CodeRead\"><arg name=\"path\">/tmp/ws1/a.kt</arg></use>"
        )
        runtime.rounds.addLast("读完了。")
        val e = engine(runtime, runner)
        e.setActiveWorkspace("ws1", "演示", File("/tmp/ws1"), null)
        val events = mutableListOf<WeaveEvent>()
        e.execute(WeaveUserInput.text("读")).toList(events)
        assertEquals("CodeRead", runner.executed.single().first)
        assertTrue(events.filterIsInstance<WeaveEvent.ToolCallComplete>().single().success)
    }
}
