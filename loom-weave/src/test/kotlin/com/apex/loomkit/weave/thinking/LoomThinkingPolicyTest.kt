package com.apex.loomkit.weave.thinking

import com.apex.loomkit.weave.WeaveThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomThinkingPolicy 测试（思考档位三重表达——此前仅默认构造，无行为锁）：
 *
 * - 梭回合预算倍率：8 档精确值 + NONE→APEXCODE 单调递增；
 * - 努力取向文案：NONE 不织入、其余档非空；
 * - 原生推理家族：大小写不敏感子串（先中先得），null/空白为 false；
 *   陷阱样例：gpt-4o（含「4o」不含「o4」）不得误判；
 * - deliberationDirective 合成：两空得空段、双有得两行。
 */
class LoomThinkingPolicyTest {

    private val policy = LoomThinkingPolicy()

    @Test
    fun roundBudgetMultipliersExact() {
        assertEquals(0.6f, policy.roundBudgetMultiplier(WeaveThinkingLevel.NONE), 1e-6f)
        assertEquals(0.8f, policy.roundBudgetMultiplier(WeaveThinkingLevel.LIGHT), 1e-6f)
        assertEquals(1.0f, policy.roundBudgetMultiplier(WeaveThinkingLevel.STANDARD), 1e-6f)
        assertEquals(1.4f, policy.roundBudgetMultiplier(WeaveThinkingLevel.DEEP), 1e-6f)
        assertEquals(1.8f, policy.roundBudgetMultiplier(WeaveThinkingLevel.MAXIMUM), 1e-6f)
        assertEquals(2.2f, policy.roundBudgetMultiplier(WeaveThinkingLevel.ULTRACODE), 1e-6f)
        assertEquals(2.5f, policy.roundBudgetMultiplier(WeaveThinkingLevel.APEXCODE), 1e-6f)
        assertEquals(1.2f, policy.roundBudgetMultiplier(WeaveThinkingLevel.AUTO), 1e-6f)
    }

    @Test
    fun budgetMonotonicFromNoneToApexcode() {
        val ladder = listOf(
            WeaveThinkingLevel.NONE, WeaveThinkingLevel.LIGHT, WeaveThinkingLevel.STANDARD,
            WeaveThinkingLevel.DEEP, WeaveThinkingLevel.MAXIMUM, WeaveThinkingLevel.ULTRACODE,
            WeaveThinkingLevel.APEXCODE
        )
        ladder.zipWithNext().forEach { (lo, hi) ->
            assertTrue(
                "预算应随档位递增：$lo → $hi",
                policy.roundBudgetMultiplier(lo) < policy.roundBudgetMultiplier(hi)
            )
        }
    }

    @Test
    fun effortHintEmptyOnlyForNone() {
        assertEquals("", policy.effortHint(WeaveThinkingLevel.NONE))
        listOf(
            WeaveThinkingLevel.LIGHT, WeaveThinkingLevel.STANDARD, WeaveThinkingLevel.DEEP,
            WeaveThinkingLevel.MAXIMUM, WeaveThinkingLevel.ULTRACODE, WeaveThinkingLevel.APEXCODE,
            WeaveThinkingLevel.AUTO
        ).forEach { lvl ->
            assertTrue("档位 $lvl 应有努力文案", policy.effortHint(lvl).isNotBlank())
        }
        // 内容抽查：极限档与 AUTO
        assertTrue(policy.effortHint(WeaveThinkingLevel.APEXCODE).contains("穷尽方案空间"))
        assertTrue(policy.effortHint(WeaveThinkingLevel.AUTO).contains("中等深度"))
    }

    @Test
    fun nativeReasoningFamilies() {
        // 命中样例（大小写不敏感子串，先中先得）
        assertTrue(policy.prefersNativeReasoning("glm-4.5-air"))
        assertTrue(policy.prefersNativeReasoning("GLM-Z1-Flight"))
        assertTrue(policy.prefersNativeReasoning("Qwen3-235B"))
        assertTrue(policy.prefersNativeReasoning("DeepSeek-R1"))
        assertTrue(policy.prefersNativeReasoning("o3-mini"))
        assertTrue(policy.prefersNativeReasoning("o4-mini-high"))
        assertTrue(policy.prefersNativeReasoning("some-thinking-model"))
        assertTrue(policy.prefersNativeReasoning("xxx-reasoner"))
        // 陷阱样例：含「4o」「t-4」不得误命中 o4/o1/o3 家族
        assertFalse(policy.prefersNativeReasoning("gpt-4o"))
        assertFalse(policy.prefersNativeReasoning("gpt-4o-mini"))
        assertFalse(policy.prefersNativeReasoning("claude-sonnet-4.5"))
        assertFalse(policy.prefersNativeReasoning("mistral-large"))
        // null / 空白
        assertFalse(policy.prefersNativeReasoning(null))
        assertFalse(policy.prefersNativeReasoning(""))
        assertFalse(policy.prefersNativeReasoning("   "))
    }

    @Test
    fun deliberationDirectiveComposition() {
        // 两空 → 空段
        assertEquals("", policy.deliberationDirective(WeaveThinkingLevel.NONE, null))
        // 仅家族偏好（NONE 不织入努力文案）
        val nativeOnly = policy.deliberationDirective(WeaveThinkingLevel.NONE, "glm-4.5")
        assertEquals("本模型自带原生推理通道——优先使用原生思考，in-band 思考块仅在需要留痕时使用。", nativeOnly)
        // 仅努力取向（非原生家族）
        assertEquals(
            policy.effortHint(WeaveThinkingLevel.STANDARD),
            policy.deliberationDirective(WeaveThinkingLevel.STANDARD, "gpt-4o")
        )
        // 双有 → 两行合成
        val both = policy.deliberationDirective(WeaveThinkingLevel.DEEP, "qwen3-max")
        assertEquals(
            policy.effortHint(WeaveThinkingLevel.DEEP) + "\n" +
                "本模型自带原生推理通道——优先使用原生思考，in-band 思考块仅在需要留痕时使用。",
            both
        )
    }
}
