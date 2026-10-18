package com.apex.loomkit.weave.loop

import com.apex.loomkit.weave.LoomEngineConfig
import com.apex.loomkit.weave.markup.LoomOutcomeFormatter
import com.apex.loomkit.weave.memory.LoomMemoryWeaver
import com.apex.loomkit.weave.permission.LoomAccessMode
import com.apex.loomkit.weave.permission.LoomApprovalBox
import com.apex.loomkit.weave.permission.LoomPermissionGovernor
import com.apex.loomkit.weave.permission.LoomPermissionSnapshot
import com.apex.loomkit.weave.permission.LoomPermissionSource
import com.apex.loomkit.weave.steer.LoomSteering
import com.apex.loomkit.weave.thinking.LoomThinkingPolicy
import com.apex.loomkit.weave.WeaveEvent
import com.apex.loomkit.weave.WeaveMode
import com.apex.loomkit.weave.WeaveUserInput
import com.apex.loomkit.weave.WeaveMessage
import com.apex.loomkit.weave.WeaveModelResponse
import com.apex.loomkit.weave.WeaveStreamChunk
import com.apex.loomkit.weave.WeaveToolCall
import com.apex.loomkit.weave.WeaveToolDefinition
import com.apex.loomkit.weave.WeaveToolStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomRoundRunner 梭回合机集成测试（剧本式模型客户端 + 假工具执行器）：
 *
 * - 纯文本终局 / 工具往返（in-band 与原生调用两路）；
 * - 权限问答闭环（越界写 → 询问 → 允许执行）；
 * - 布样人控门（PLAN 模式 → 确认 → 按步执行）；
 * - 瞬时故障重试阶梯 / 空转终止 / 纯思考催促 / 中止收场。
 */
class LoomRoundRunnerTest {

    /** 剧本：一回合 = 一批流帧 或 一次抛错。 */
    private sealed interface Script {
        data class Chunks(val chunks: List<WeaveStreamChunk>) : Script
        data class Fail(val t: Throwable) : Script
    }

    private class ScriptedClient : LoomModelClient {
        val rounds = ArrayDeque<Script>()

        override fun chatStream(
            contextTag: String,
            messages: List<WeaveMessage>,
            tools: List<WeaveToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<WeaveStreamChunk> = flow {
            when (val script = rounds.removeFirst()) {
                is Script.Chunks -> script.chunks.forEach { emit(it) }
                is Script.Fail -> throw script.t
            }
        }

        override suspend fun chat(
            contextTag: String,
            messages: List<WeaveMessage>,
            temperature: Float,
            maxTokens: Int
        ): WeaveModelResponse = WeaveModelResponse(content = "摘要正文")
    }

    private class FakeToolRunner : LoomToolRunner {
        val executed = mutableListOf<Pair<String, String>>()

        override fun executeStream(toolId: String, arguments: String): Flow<WeaveToolStream> =
            flow {
                executed.add(toolId to arguments)
                emit(WeaveToolStream.Output("部分输出 "))
                emit(WeaveToolStream.Progress(percent = 0.5f, message = "进行中"))
                emit(WeaveToolStream.Complete("[$toolId] 完整输出"))
            }
    }

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

    private val logger = object : LoomLogger {
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private class Fixture(
        val client: ScriptedClient = ScriptedClient(),
        val tools: FakeToolRunner = FakeToolRunner(),
        val memory: RecordingMemory = RecordingMemory(),
        val session: LoomSession,
        val runner: LoomRoundRunner,
        val steering: LoomSteering
    )

    private fun fixture(
        mode: WeaveMode = WeaveMode.BUILD,
        accessMode: LoomAccessMode = LoomAccessMode.FABRIC,
        workspaceRoot: File? = File("/tmp/loom-ws"),
        config: LoomEngineConfig = LoomEngineConfig(shuttleRoundsMax = 8)
    ): Fixture {
        val client = ScriptedClient()
        val tools = FakeToolRunner()
        val memory = RecordingMemory()
        val session = LoomSession(
            config = config,
            memory = null,
            estimator = LoomHeuristicEstimator,
            environmentSource = { LoomEnvironmentInfo(platformText = "测试机") },
            workspaceContextSource = null,
            modelInfoSource = null,
            logger = logger
        )
        session.bindWorkspace("test", "测试", workspaceRoot, null)
        session.updateMode(mode)
        val steering = LoomSteering(config)
        val governor = LoomPermissionGovernor(
            LoomPermissionSource { LoomPermissionSnapshot(mode = accessMode) }
        )
        val weaver = LoomMemoryWeaver(config, client, LoomHeuristicEstimator, logger)
        val runner = LoomRoundRunner(
            config = config,
            session = session,
            modelClient = client,
            toolSource = LoomToolSource {
                listOf(
                    WeaveToolDefinition("CodeRead", "读文件", "{\"type\":\"object\"}"),
                    WeaveToolDefinition("CodeWrite", "写文件", "{\"type\":\"object\"}")
                )
            },
            toolRunner = tools,
            governor = governor,
            weaver = weaver,
            steering = steering,
            approvalBox = LoomApprovalBox(),
            planGate = LoomPlanGate(),
            formatter = LoomOutcomeFormatter(config),
            thinkingPolicy = LoomThinkingPolicy(),
            failureClassifier = { t ->
                when (t) {
                    is IOException -> LoomFailureKind.TRANSIENT
                    else -> LoomFailureKind.UNCLASSIFIED
                }
            },
            logger = logger,
            abortSignal = LoomAbortSignal(),
            modelInfoSource = null,
            clock = { 1_000L },
            retrySleeper = { }
        )
        return Fixture(client, tools, memory, session, runner, steering)
    }

    private fun textRound(vararg pieces: String): Script = Script.Chunks(
        pieces.flatMap { p ->
            // 按字符切两半喂入——覆盖跨块截断路径
            if (p.length <= 1) listOf(WeaveStreamChunk(content = p)) else listOf(
                WeaveStreamChunk(content = p.substring(0, p.length / 2)),
                WeaveStreamChunk(content = p.substring(p.length / 2))
            )
        } + listOf(WeaveStreamChunk(isFinish = true, finishReason = "stop"))
    )

    // ═══════════════════════════ 纯文本终局 ═══════════════════════════

    @Test
    fun `plain reply completes in one round`() = runTest {
        val f = fixture()
        f.client.rounds.addLast(textRound("你好，任务完成。"))
        val events = mutableListOf<WeaveEvent>()
        f.runner.run(WeaveUserInput.text("打个招呼")).toList(events)
        assertTrue(events.any { it is WeaveEvent.IterationStart })
        assertTrue(events.any { it is WeaveEvent.ThinkingStart })
        assertTrue(events.filterIsInstance<WeaveEvent.ResponseChunk>().isNotEmpty())
        assertEquals("你好，任务完成。", events.filterIsInstance<WeaveEvent.ResponseComplete>().single().fullText)
        val complete = events.filterIsInstance<WeaveEvent.Complete>().single()
        assertEquals(1, complete.totalIterations)
        assertEquals(0, complete.totalToolCalls)
    }

    // ═══════════════════════════ 工具往返 ═══════════════════════════

    @Test
    fun `in-band tool call round trip executes and weaves outcome`() = runTest {
        val f = fixture()
        f.client.rounds.addLast(
            textRound(
                "我先读文件。",
                "<use name=\"CodeRead\"><arg name=\"path\">/tmp/loom-ws/a.kt</arg></use>"
            )
        )
        f.client.rounds.addLast(textRound("读取完成，内容是……"))
        val events = mutableListOf<WeaveEvent>()
        f.runner.run(WeaveUserInput.text("读一下 a.kt")).toList(events)
        assertEquals(1, f.tools.executed.size)
        assertEquals("CodeRead", f.tools.executed.single().first)
        assertTrue(events.any { it is WeaveEvent.ToolCallStart })
        assertTrue(events.any { it is WeaveEvent.ToolProgress })
        assertTrue(events.any { it is WeaveEvent.ToolOutputChunk })
        val settled = events.filterIsInstance<WeaveEvent.ToolCallComplete>().single()
        assertTrue(settled.success)
        // 回执轮织入历史（含 outcome 块）
        val history = f.session.historySnapshot()
        val outcomeTurn = history.filterIsInstance<WeaveMessage.User>()
            .first { it.content.contains("<outcome_") }
        assertTrue(outcomeTurn.content.contains("name=\"CodeRead\""))
        assertTrue(outcomeTurn.content.contains("status=\"ok\""))
    }

    @Test
    fun `native tool calls are bridged into the same protocol`() = runTest {
        val f = fixture()
        f.client.rounds.addLast(
            Script.Chunks(
                listOf(
                    WeaveStreamChunk(content = "走原生通道。"),
                    WeaveStreamChunk(
                        toolCalls = listOf(
                            WeaveToolCall(id = "n1", name = "CodeRead", arguments = "{\"path\":\"/tmp/loom-ws/b.kt\"}", index = 0)
                        )
                    ),
                    WeaveStreamChunk(isFinish = true, finishReason = "tool_calls")
                )
            )
        )
        f.client.rounds.addLast(textRound("原生通道同样落地。"))
        val events = mutableListOf<WeaveEvent>()
        f.runner.run(WeaveUserInput.text("读 b.kt")).toList(events)
        assertEquals("CodeRead", f.tools.executed.single().first)
        // 助手轮里是 use 块（in-band）而非原生 toolCalls
        val assistantTurns = f.session.historySnapshot().filterIsInstance<WeaveMessage.Assistant>()
        assertTrue(assistantTurns.first().content.contains("<use name=\"CodeRead\">"))
        assertTrue(assistantTurns.first().toolCalls.isEmpty())
    }

    // ═══════════════════════════ 权限问答闭环 ═══════════════════════════

    @Test
    fun `out-of-workspace write asks and approval executes`() = runTest {
        val f = fixture()
        f.client.rounds.addLast(
            textRound(
                "<use name=\"CodeWrite\"><arg name=\"path\">/tmp/outside/x.kt</arg>" +
                    "<arg name=\"content\">hi</arg></use>"
            )
        )
        f.client.rounds.addLast(textRound("写入完成。"))
        val events = mutableListOf<WeaveEvent>()
        val job = launch { f.runner.run(WeaveUserInput.text("写到外面")).toList(events) }
        runCurrent() // 推进到权限问答挂起（不越过超时窗）
        assertTrue(events.any { it is WeaveEvent.UserInputRequired })
        org.junit.Assert.assertTrue(f.runner.submitApprovalAnswer("允许"))
        advanceUntilIdle()
        job.join()
        assertEquals(1, f.tools.executed.size) // 批准后真实执行
        assertTrue(events.any { it is WeaveEvent.Complete })
    }

    @Test
    fun `approval denial skips execution and weaves warn outcome`() = runTest {
        val f = fixture()
        f.client.rounds.addLast(
            textRound(
                "<use name=\"CodeWrite\"><arg name=\"path\">/tmp/outside/x.kt</arg>" +
                    "<arg name=\"content\">hi</arg></use>"
            )
        )
        f.client.rounds.addLast(textRound("好的，那我换个思路。"))
        val events = mutableListOf<WeaveEvent>()
        val job = launch { f.runner.run(WeaveUserInput.text("写到外面")).toList(events) }
        runCurrent()
        org.junit.Assert.assertTrue(f.runner.submitApprovalAnswer("拒绝"))
        advanceUntilIdle()
        job.join()
        assertEquals(0, f.tools.executed.size) // 未执行
        val history = f.session.historySnapshot()
        val outcomeTurn = history.filterIsInstance<WeaveMessage.User>()
            .first { it.content.contains("<outcome_") }
        assertTrue(outcomeTurn.content.contains("用户拒绝"))
    }

    // ═══════════════════════════ 布样人控门 ═══════════════════════════

    @Test
    fun `plan mode gates on woven plan and executes after confirm`() = runTest {
        val f = fixture(mode = WeaveMode.PLAN)
        f.client.rounds.addLast(
            textRound(
                "我的计划如下：",
                "<loom-plan goal=\"修好构建\">" +
                    "<step>先复现</step><step>再修复</step>" +
                    "</loom-plan>"
            )
        )
        f.client.rounds.addLast(
            textRound(
                "开始执行第一步。",
                "<use name=\"CodeRead\"><arg name=\"path\">/tmp/loom-ws/c.kt</arg></use>"
            )
        )
        f.client.rounds.addLast(textRound("全部步骤完成，构建已修好。"))
        val events = mutableListOf<WeaveEvent>()
        val job = launch { f.runner.run(WeaveUserInput.text("修构建")).toList(events) }
        runCurrent() // 布样出示 + 挂起待决（不越过窗口）
        assertTrue(events.any { it is WeaveEvent.PlanGenerated })
        assertTrue(events.any { it is WeaveEvent.PlanAwaitingConfirmation })
        org.junit.Assert.assertTrue(f.runner.submitPlanConfirmation(true))
        advanceUntilIdle()
        job.join()
        assertTrue(events.any { it is WeaveEvent.PlanConfirmed })
        assertEquals(1, f.tools.executed.size)
        // 确认后提示词切换（布样已确认段）
        assertTrue(f.session.assembleSystemPrompt(emptyList()).contains("按已确认的步骤"))
    }

    // ═══════════════════════════ 容错与守卫 ═══════════════════════════

    @Test
    fun `transient failure retries with scheduled notice`() = runTest {
        val f = fixture()
        f.client.rounds.addLast(Script.Fail(IOException("网络抖动")))
        f.client.rounds.addLast(textRound("重试成功。"))
        val events = mutableListOf<WeaveEvent>()
        f.runner.run(WeaveUserInput.text("任务")).toList(events)
        val retry = events.filterIsInstance<WeaveEvent.LlmRetryScheduled>().single()
        assertEquals(1, retry.attempt)
        assertTrue(events.any { it is WeaveEvent.ResponseComplete })
    }

    @Test
    fun `repeated identical batches converge`() = runTest {
        val f = fixture()
        val batch = "<use name=\"CodeRead\"><arg name=\"path\">/tmp/loom-ws/a.kt</arg></use>"
        repeat(4) { f.client.rounds.addLast(textRound(batch)) }
        f.client.rounds.addLast(textRound("总结：文件没问题。"))
        val events = mutableListOf<WeaveEvent>()
        f.runner.run(WeaveUserInput.text("反复读")).toList(events)
        // 第 4 批被强制收敛——只执行了 3 次
        assertEquals(3, f.tools.executed.size)
        assertTrue(events.filterIsInstance<WeaveEvent.ResponseChunk>().any { it.text.contains("系统提示") })
    }

    @Test
    fun `pure deliberation gets nudged once then completes`() = runTest {
        val f = fixture()
        f.client.rounds.addLast(
            Script.Chunks(
                listOf(
                    WeaveStreamChunk(reasoningContent = "想想…"),
                    WeaveStreamChunk(isFinish = true, finishReason = "stop")
                )
            )
        )
        f.client.rounds.addLast(textRound("想清楚了：直接回答。"))
        val events = mutableListOf<WeaveEvent>()
        f.runner.run(WeaveUserInput.text("想")).toList(events)
        assertTrue(events.filterIsInstance<WeaveEvent.ThinkingChunk>().isNotEmpty())
        // 催促轮织入历史
        assertTrue(f.session.historySnapshot().any { it is WeaveMessage.User && it.content.contains("系统提示") })
        assertTrue(events.any { it is WeaveEvent.ResponseComplete })
    }

    @Test
    fun `abort during approval produces aborted event`() = runTest {
        val f = fixture()
        f.client.rounds.addLast(
            textRound(
                "<use name=\"CodeWrite\"><arg name=\"path\">/tmp/outside/x.kt</arg>" +
                    "<arg name=\"content\">hi</arg></use>"
            )
        )
        val events = mutableListOf<WeaveEvent>()
        val job = launch { f.runner.run(WeaveUserInput.text("任务")).toList(events) }
        runCurrent()
        assertTrue(events.any { it is WeaveEvent.UserInputRequired })
        f.runner.requestAbort() // 问答抢占收口 + 信号置位
        advanceUntilIdle()
        job.join()
        assertTrue(events.any { it is WeaveEvent.Aborted })
        assertTrue(events.any { it is WeaveEvent.Complete })
        assertEquals(0, f.tools.executed.size)
    }

    @Test
    fun `steering notes are woven at round boundary`() = runTest {
        val f = fixture()
        // 用问答挂起制造运行中窗口（转向仅运行中受理）
        f.client.rounds.addLast(
            textRound(
                "<use name=\"CodeWrite\"><arg name=\"path\">/tmp/outside/x.kt</arg>" +
                    "<arg name=\"content\">hi</arg></use>"
            )
        )
        f.client.rounds.addLast(textRound("收到转向，按新指令办。"))
        val events = mutableListOf<WeaveEvent>()
        val job = launch { f.runner.run(WeaveUserInput.text("写文件")).toList(events) }
        runCurrent()
        org.junit.Assert.assertTrue(f.runner.offerSteering("改用方案B"))
        org.junit.Assert.assertTrue(f.runner.submitApprovalAnswer("允许"))
        advanceUntilIdle()
        job.join()
        assertTrue(
            events.filterIsInstance<WeaveEvent.ResponseChunk>()
                .any { it.text.contains("[steering]") }
        )
        assertTrue(
            f.session.historySnapshot().any {
                it is WeaveMessage.User && it.content.contains("改用方案B")
            }
        )
    }
}
