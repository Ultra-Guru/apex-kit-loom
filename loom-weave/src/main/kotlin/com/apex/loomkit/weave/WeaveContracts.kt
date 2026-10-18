package com.apex.loomkit.weave

import kotlinx.coroutines.flow.Flow

/**
 * # Weave Contracts — 织造库自有协议类型
 *
 * 库内核**零宿主依赖**的可执行证明：消息 / 流帧 / 工具面 / 事件 / 输入 /
 * 档位全部是库自有类型（与任何宿主的 UI 协议逐型映射——宿主侧适配器
 * 十行一级完成双向转换，见库 README「端口-适配器」）。
 *
 * | 类型 | 职责 | 宿主映射示例 |
 * |---|---|---|
 * | [WeaveMessage] | 会话消息（四角色） | 宿主 LLM 消息模型 |
 * | [WeaveStreamChunk] / [WeaveModelResponse] | 流式帧 / 非流式响应 | 宿主 LLM 客户端协议 |
 * | [WeaveToolDefinition] / [WeaveToolCall] | 工具目录 / 调用 | 宿主工具注册表面 |
 * | [WeaveToolStream] | 工具流式输出四态 | 宿主流式工具事件 |
 * | [WeaveEvent] | 引擎事件（22 型） | 宿主引擎事件逐型映射 |
 * | [WeaveUserInput] / [WeaveFileRef] | 多模态输入 | 宿主用户输入 |
 * | [WeaveMode] / [WeaveThinkingLevel] | 模式 / 思考档位 | 宿主枚举按名映射 |
 */
// ═══════════════════════ 消息协议 ═══════════════════════

/** 会话消息（四角色——in-band 协议下助手轮零原生 toolCalls 字段）。 */
sealed interface WeaveMessage {
    data class System(val content: String) : WeaveMessage

    data class User(
        val content: String,
        val images: List<WeaveImageContent> = emptyList()
    ) : WeaveMessage

    data class Assistant(
        val content: String,
        val toolCalls: List<WeaveToolCall> = emptyList()
    ) : WeaveMessage

    data class ToolResult(val toolCallId: String, val content: String) : WeaveMessage
}

/** 多模态输入图片（base64 内联或远程直链二选一）。 */
data class WeaveImageContent(
    val base64Data: String,
    val mimeType: String = "image/jpeg",
    val detail: String = "auto",
    /** 远程图片直链（非空时直传 URL，忽略 base64 字段）。 */
    val url: String? = null
)

/** 非流式响应。 */
data class WeaveModelResponse(
    val content: String?,
    val toolCalls: List<WeaveToolCall> = emptyList(),
    val usage: WeaveUsage? = null,
    val images: List<String> = emptyList(),
    val videos: List<String> = emptyList()
)

/**
 * 流式增量帧。
 *
 * [reasoningContent] 透传 provider 原生推理增量（o-series / R1 系 /
 * thinking 家族）；[finishReason] 透传原始收尾原因（`length` = 输出截断
 * ——回合机据此触发断点续跑）；[usage] 携带流尾真实用量统计帧。
 */
data class WeaveStreamChunk(
    val content: String? = null,
    val toolCalls: List<WeaveToolCall> = emptyList(),
    val isFinish: Boolean = false,
    val finishReason: String? = null,
    val reasoningContent: String? = null,
    val images: List<String> = emptyList(),
    val videos: List<String> = emptyList(),
    val usage: WeaveUsage? = null
)

/** 一次原生工具调用（[index] 为并行流式装配的归并键）。 */
data class WeaveToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val index: Int = -1
)

/** 真实用量统计。 */
data class WeaveUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/** 工具目录条目（parameters 为 JSON Schema 文本）。 */
data class WeaveToolDefinition(
    val name: String,
    val description: String,
    val parameters: String
)

// ═══════════════════════ 工具流式输出 ═══════════════════════

/**
 * 工具流式输出事件（四态）。
 *
 * 一个流式工具在执行流中发射零或多个 [WeaveToolStream.Output] /
 * [WeaveToolStream.Progress]，最后以 [WeaveToolStream.Complete]（成功）
 * 或 [WeaveToolStream.Error]（失败）收尾；流自然结束也是合法终止信号
 * （引擎把已累积的 Output 作为最终结果）。
 */
sealed interface WeaveToolStream {

    /** 普通输出片段（引擎拼接并逐段推送 UI）。 */
    data class Output(val chunk: String) : WeaveToolStream

    /** 进度事件（percent 0..1）。 */
    data class Progress(
        val percent: Float? = null,
        val message: String? = null
    ) : WeaveToolStream

    /** 正常完成（output 携带完整最终输出）。 */
    data class Complete(val output: String) : WeaveToolStream

    /** 失败（message 追加到累积输出，调用按失败收口）。 */
    data class Error(val message: String) : WeaveToolStream
}

// ═══════════════════════ 用户输入 ═══════════════════════

/** 用户多模态输入（文本 + 图片 + 文件引用）。 */
data class WeaveUserInput(
    val text: String,
    val images: List<WeaveImageContent> = emptyList(),
    val files: List<WeaveFileRef> = emptyList()
) {
    companion object {
        /** 纯文本输入的便捷构造。 */
        fun text(text: String): WeaveUserInput = WeaveUserInput(text = text)
    }
}

/** 文件附件引用（路径 + 元信息——由工具进一步读取）。 */
data class WeaveFileRef(
    val name: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long
)

/** 问答输入类型（权限问答用 [WeaveInputType.CONFIRMATION]）。 */
enum class WeaveInputType {
    CONFIRMATION,
    TEXT,
    CHOICE
}

// ═══════════════════════ 模式与档位 ═══════════════════════

/**
 * 会话模态（宿主枚举按名映射）。
 *
 * 织造线消费 [WeaveMode.PLAN]（布样人控门）与 [WeaveMode.BUILD]（默认）；
 * 其余档位为映射完备性保留（提示词不分支）。
 */
enum class WeaveMode {
    CHAT, AGENT, BUILD, PLAN, GOAL, LOOP, SPEC, REFLECTION, HUMAN_ASSIST, CUSTOM
}

/**
 * 思考档位（八档——宿主 Coding 七档 + AUTO 元档，按名映射）。
 *
 * 序数即深度序（引擎内可直接比较）；ULTRACODE / APEXCODE 为编码深水档，
 * AUTO 为元档（宿主侧发送前预检解析，引擎只接收具体深度档——兜底口径
 * 见 [LoomThinkingPolicy]）。
 */
enum class WeaveThinkingLevel {
    NONE, LIGHT, STANDARD, DEEP, MAXIMUM, ULTRACODE, APEXCODE, AUTO
}

// ═══════════════════════ 事件协议 ═══════════════════════

/** 布样步骤（[WeaveEvent.PlanGenerated] 系列载荷）。 */
data class WeavePlanStep(
    val index: Int,
    val description: String,
    val toolName: String? = null,
    val estimatedArgs: String? = null,
    val dependsOn: List<Int> = emptyList()
)

/** 布样风险档（织造线恒 MEDIUM——结构化占位）。 */
enum class WeaveRiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

/** 结构化布样（PLAN 模式首轮产物）。 */
data class WeaveExecutionPlan(
    val goal: String,
    val steps: List<WeavePlanStep>,
    val estimatedToolCalls: Int,
    val riskLevel: WeaveRiskLevel,
    val reasoning: String
)

/**
 * 织造引擎事件（22 型——宿主 UI 事件协议逐型映射）。
 *
 * 全部产出走本协议：胶囊时间轴 / 长任务追踪 / 权限问答 UI 零改动消费。
 */
sealed interface WeaveEvent {

    /** 迭代开始（一梭）。 */
    data class IterationStart(val iteration: Int) : WeaveEvent

    /** 回合思考开始（档位快照）。 */
    data class ThinkingStart(
        val iteration: Int,
        val thinkingLevel: WeaveThinkingLevel
    ) : WeaveEvent

    /** 思考内容增量。 */
    data class ThinkingChunk(val text: String) : WeaveEvent

    /** 回合思考完成。 */
    data class ThinkingComplete(val fullThought: String) : WeaveEvent

    /** 正文回复增量。 */
    data class ResponseChunk(val text: String) : WeaveEvent

    /** 回复完成。 */
    data class ResponseComplete(val fullText: String) : WeaveEvent

    /** 布样生成（PLAN 模式首轮）。 */
    data class PlanGenerated(val plan: WeaveExecutionPlan) : WeaveEvent

    /** 布样等待用户确认。 */
    data class PlanAwaitingConfirmation(val plan: WeaveExecutionPlan) : WeaveEvent

    /** 布样已确认。 */
    data class PlanConfirmed(val plan: WeaveExecutionPlan) : WeaveEvent

    /** 工具调用开始。 */
    data class ToolCallStart(
        val callId: String,
        val toolName: String,
        val arguments: String
    ) : WeaveEvent

    /** 工具输出增量（流式）。 */
    data class ToolOutputChunk(val callId: String, val chunk: String) : WeaveEvent

    /** 工具进度（流式）。 */
    data class ToolProgress(
        val callId: String,
        val percent: Float?,
        val message: String?
    ) : WeaveEvent

    /** 工具调用完成（统一收口口径）。 */
    data class ToolCallComplete(
        val callId: String,
        val toolName: String,
        val arguments: String = "",
        val output: String,
        val fullOutput: String = "",
        val success: Boolean,
        val durationMs: Long
    ) : WeaveEvent

    /** 真实用量更新（流尾统计帧到达）。 */
    data class UsageUpdated(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int
    ) : WeaveEvent

    /** 上下文已压缩（digest 织入）。 */
    data class ContextCompressed(
        val beforeTokens: Int,
        val afterTokens: Int,
        val strategy: String,
        val summary: String,
        val messagesRemoved: Int,
        val messagesTruncated: Int = 0
    ) : WeaveEvent

    /** 需要用户输入/确认（权限问答）。 */
    data class UserInputRequired(
        val prompt: String,
        val type: WeaveInputType = WeaveInputType.CONFIRMATION
    ) : WeaveEvent

    /** 问答等待超时（按拒绝语义收口）。 */
    data object UserInputExpired : WeaveEvent

    /** LLM 容错调度通知（重试 / 降级续跑——过程可见性，非终局）。 */
    data class LlmRetryScheduled(
        val attempt: Int,
        val delayMs: Long,
        val reason: String
    ) : WeaveEvent

    /** 错误（recoverable 与否都收口本任务）。 */
    data class Error(
        val message: String,
        val recoverable: Boolean = true
    ) : WeaveEvent

    /** 全部完成。 */
    data class Complete(
        val summary: String,
        val totalIterations: Int,
        val totalToolCalls: Int,
        val totalDurationMs: Long
    ) : WeaveEvent

    /** 被中止（协作式中止——Aborted 后紧跟 Complete 正常收场）。 */
    data object Aborted : WeaveEvent
}
