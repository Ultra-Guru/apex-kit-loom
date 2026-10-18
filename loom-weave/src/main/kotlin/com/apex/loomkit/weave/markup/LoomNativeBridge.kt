package com.apex.loomkit.weave.markup

import com.apex.loomkit.weave.LoomArg
import com.apex.loomkit.weave.LoomInvocation
import com.apex.loomkit.weave.WeaveStreamChunk
import com.apex.loomkit.weave.WeaveToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * # Loom Native Bridge — 原生函数调用 → 织造标记桥接器
 *
 * 把 provider 的原生流式帧（[WeaveStreamChunk]：content / reasoning /
 * toolCalls 增量 / usage / finish）翻译成**单一织物文本流**
 * （[LoomWireFrame.Text]，标记已规范化）+ 计量/收尾帧——循环内只有
 * 一种协议，解析器无需感知原生调用。
 *
 * ## 翻译规则
 *
 * | 原生帧 | 织物帧 |
 * |---|---|
 * | content 增量 | Text（原样——Markdown 正文不动） |
 * | reasoning 增量 | 缓冲；块闭合（content / 工具帧到达）或流收尾时整块织出 Text（包裹进 deliberate 块——开闭标签永不悬空） |
 * | toolCalls 增量 | 装配（index 归并）；完成时以 use 块文本织入 |
 * | usage | Usage（直通） |
 * | isFinish | Finish（直传 finishReason） |
 *
 * 调用块的织入时机：**内容帧在工具帧之后到达**（模型先发调用再补文，
 * 说明调用已完整）或**流收尾**（finish）——两处都触发装配刷出，保持
 * 文本/调用的交错次序。
 *
 * ## 参数保真
 *
 * 装配完成的 arguments JSON 原文存入 [LoomInvocation.rawJson]（嵌套
 * 结构零失真）；同时展开为 [LoomArg] 明文表（展示/审计用）。
 *
 * ## 防御纪律
 *
 * - arguments 不是合法 JSON 对象 → 整块按**原文 use 块**渲染降级
 *   （把原始字符串作为单参数透传，工具端自会报参数错误回执）；
 * - 名字为空的增量（流被腰斩）→ 丢弃该装配项；
 * - reasoning 与 content 交替时，deliberate 块正确开闭（不留悬空标签）。
 *
 * 线程契约：单协程顺序调用。
 */
class LoomNativeBridge {

    /** 规范化后的织物帧。 */
    sealed interface LoomWireFrame {
        /** 织物文本增量（Markdown / deliberate 块 / use 块都在这里）。 */
        data class Text(val delta: String) : LoomWireFrame

        /** 真实用量帧。 */
        data class UsageFrame(
            val promptTokens: Int,
            val completionTokens: Int,
            val totalTokens: Int
        ) : LoomWireFrame

        /** 流收尾帧（reason 为 provider 原始 finishReason，可空）。 */
        data class Finish(val reason: String?) : LoomWireFrame
    }

    /** 原生调用装配项。 */
    private data class Assembling(
        val index: Int,
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    private val assembling = LinkedHashMap<Int, Assembling>()
    private val reasoningBuffer = StringBuilder()
    private var reasoningOpen = false
    private var sawToolFrames = false
    private var finished = false

    // ═══════════════════════════ 对外 API ═══════════════════════════

    /** 喂入一帧原生流增量，返回规范化织物帧（可能多条/零条）。 */
    fun feed(chunk: WeaveStreamChunk): List<LoomWireFrame> {
        val frames = mutableListOf<LoomWireFrame>()
        val reasoning = chunk.reasoningContent
        val content = chunk.content
        // reasoning 增量进缓冲——整块在「块闭合」（content / 工具帧到达）
        // 或流收尾时一次性织出（开闭标签永不悬空，块内文本零拆散）。
        if (!reasoning.isNullOrEmpty()) {
            reasoningOpen = true
            reasoningBuffer.append(reasoning)
        }
        val hasContent = !content.isNullOrEmpty()
        if ((hasContent || chunk.toolCalls.isNotEmpty()) && reasoningOpen) {
            flushDeliberation(frames)
        }
        if (!content.isNullOrEmpty()) {
            // 内容帧在工具帧之后到达 → 已装配的调用先行刷出（保交错序）
            if (sawToolFrames) {
                flushAssembled(frames)
            }
            frames.add(LoomWireFrame.Text(content))
        }
        for (call in chunk.toolCalls) {
            acceptCallDelta(call)
            sawToolFrames = true
        }
        chunk.usage?.let {
            frames.add(LoomWireFrame.UsageFrame(it.promptTokens, it.completionTokens, it.totalTokens))
        }
        if (chunk.isFinish) {
            frames.add(LoomWireFrame.Finish(chunk.finishReason))
        }
        return frames
    }

    /** 流收尾：刷出悬空思考块（整块织出）+ 残余装配。 */
    fun finish(): List<LoomWireFrame> {
        if (finished) return emptyList()
        finished = true
        val frames = mutableListOf<LoomWireFrame>()
        if (reasoningOpen) {
            flushDeliberation(frames)
        }
        flushAssembled(frames)
        return frames
    }

    /** 复位（新回合复用）。 */
    fun reset() {
        assembling.clear()
        reasoningBuffer.setLength(0)
        reasoningOpen = false
        sawToolFrames = false
        finished = false
    }

    // ═══════════════════════════ 装配与刷出 ═══════════════════════════

    /** 把缓冲中的思考文本整块织出（开标签 + 全文 + 闭标签一帧）。 */
    private fun flushDeliberation(frames: MutableList<LoomWireFrame>) {
        frames.add(LoomWireFrame.Text(OPEN_DELIB + reasoningBuffer + CLOSE_DELIB))
        reasoningBuffer.setLength(0)
        reasoningOpen = false
    }

    /** 吸收一帧原生调用增量（index 归并；index 无效时按到达序占位）。 */
    private fun acceptCallDelta(call: WeaveToolCall) {
        val key = if (call.index >= 0) call.index else -1 - assembling.size
        val slot = assembling.getOrPut(key) { Assembling(index = key) }
        if (call.name.isNotEmpty()) slot.name += call.name
        slot.arguments.append(call.arguments)
    }

    /** 把已装配的原生调用全部织成 use 块文本帧（并清空装配）。 */
    private fun flushAssembled(frames: MutableList<LoomWireFrame>) {
        if (assembling.isEmpty()) return
        val text = StringBuilder()
        for (slot in assembling.values) {
            if (slot.name.isBlank()) continue // 腰斩增量：丢弃
            val invocation = toInvocation(slot)
            text.append('\n').append(
                if (invocation.rawSpan.isNotBlank()) invocation.rawSpan
                else LoomMarkupGrammar.renderUseBlock(invocation.name, invocation.args)
            )
        }
        assembling.clear()
        sawToolFrames = false
        if (text.isNotEmpty()) {
            frames.add(LoomWireFrame.Text(text.toString()))
        }
    }

    /** 装配项 → 调用（rawJson 保真 + args 明文展开）。 */
    private fun toInvocation(slot: Assembling): LoomInvocation {
        val rawArgs = slot.arguments.toString()
        val parsed = parseFlatArgs(rawArgs)
        return if (parsed != null) {
            LoomInvocation(
                name = slot.name,
                args = parsed,
                rawJson = rawArgs.ifBlank { "{}" },
                rawSpan = LoomMarkupGrammar.renderUseBlock(slot.name, parsed)
            )
        } else {
            // 非法 JSON：单参数「raw」透传降级（工具端报参数错误回执）
            val fallback = listOf(LoomArg(name = "raw", value = rawArgs))
            LoomInvocation(
                name = slot.name,
                args = fallback,
                rawJson = null,
                rawSpan = LoomMarkupGrammar.renderUseBlock(slot.name, fallback)
            )
        }
    }

    /**
     * 原生参数 JSON → 明文 arg 表（保序；嵌套值回串为 JSON 文本）。
     *
     * @return null = 不是 JSON 对象（降级路径）。
     */
    private fun parseFlatArgs(rawArgs: String): List<LoomArg>? {
        if (rawArgs.isBlank()) return emptyList()
        return try {
            val obj = Json.parseToJsonElement(rawArgs).jsonObject
            obj.entries.map { (key, value) ->
                val text = when (value) {
                    is JsonPrimitive -> value.content
                    is JsonObject -> value.toString() // 嵌套对象回串（展示用）
                    else -> value.toString()
                }
                LoomArg(name = key, value = text)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private companion object {
        const val OPEN_DELIB = "<deliberate>"
        const val CLOSE_DELIB = "</deliberate>"
    }
}
