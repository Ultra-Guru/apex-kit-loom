package com.apex.loomkit.weave.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomToolRiskBook 测试（工具读写画像册——此前仅 3 条启发断言，内置册/敏感册零锁）：
 *
 * - 内置册 defaultBook：code_* 读写件 / 文件四件 / web / 技能面精确画像；
 * - effectOf 双层：内置册优先，缺席走启发（未知 MCP 一律 WRITE 保守）；
 * - isSensitive 仅内置册独裁（启发不猜敏感，防误报）；CodeGitPush 出仓敏感。
 */
class LoomToolRiskBookTest {

    @Test
    fun defaultBookEffects() {
        val book = LoomToolRiskBook.defaultBook()
        // code_* 读件
        listOf(
            "CodeRead", "CodeGlob", "CodeGrep", "CodeCheck",
            "CodeGitStatus", "CodeGitDiff", "CodeGitLog"
        ).forEach { assertEquals("READ expected: $it", LoomToolEffect.READ, book.effectOf(it)) }
        // code_* 写件
        listOf(
            "CodeWrite", "CodeEdit", "CodeTodo", "CodeGitCommit", "CodeGitBranch",
            "CodeGitPush"
        ).forEach { assertEquals("WRITE expected: $it", LoomToolEffect.WRITE, book.effectOf(it)) }
        // 深潜线共享（文件 / 终端 / web / 技能 / 子代理）
        listOf("FileRead", "FileGlob", "Grep", "Glob", "WebSearch", "WebFetch",
            "SkillActivate", "SkillList", "CodeTask").forEach {
            assertEquals("READ expected: $it", LoomToolEffect.READ, book.effectOf(it))
        }
        listOf("FileWrite", "FileEdit", "ShellExecute").forEach {
            assertEquals("WRITE expected: $it", LoomToolEffect.WRITE, book.effectOf(it))
        }
    }

    @Test
    fun defaultBookSensitivity() {
        val book = LoomToolRiskBook.defaultBook()
        // 出仓推送 = 结构性敏感；其余内置件不登记敏感
        assertTrue(book.isSensitive("CodeGitPush"))
        assertFalse(book.isSensitive("CodeGitCommit"))
        assertFalse(book.isSensitive("FileWrite"))
        assertFalse(book.isSensitive("ShellExecute"))
        assertFalse(book.isSensitive("mcp__x__destroy_world"))
    }

    @Test
    fun effectOfFallsBackToHeuristic() {
        val book = LoomToolRiskBook.defaultBook()
        // 未收录工具 → 命名启发（write 动词 → WRITE；其余 READ；未知 MCP 保守 WRITE）
        assertEquals(LoomToolEffect.WRITE, book.effectOf("BrandNewWrite"))
        assertEquals(LoomToolEffect.WRITE, book.effectOf("mcp__x__do_thing"))
        assertEquals(LoomToolEffect.READ, book.effectOf("BrandNewThing"))
        assertEquals(LoomToolEffect.READ, book.effectOf("QueryIndex"))
        // 启发命中清单抽查（install / create / reset 均在 WRITE_HINTS）
        assertEquals(LoomToolEffect.WRITE, LoomToolRiskBook.effectByHeuristic("PkgInstall"))
        assertEquals(LoomToolEffect.WRITE, LoomToolRiskBook.effectByHeuristic("TokenCreate"))
        assertEquals(LoomToolEffect.WRITE, LoomToolRiskBook.effectByHeuristic("CacheReset"))
    }

    @Test
    fun customBookOverridesHeuristic() {
        val book = LoomToolRiskBook(
            effects = mapOf("X" to LoomToolEffect.WRITE),
            sensitiveIds = setOf("X")
        )
        // 内置册优先：「X」按启发本会是 READ，册子判 WRITE
        assertEquals(LoomToolEffect.WRITE, book.effectOf("X"))
        assertTrue(book.isSensitive("X"))
        // 册外仍走启发
        assertEquals(LoomToolEffect.READ, book.effectOf("Something"))
        assertEquals(LoomToolEffect.WRITE, book.effectOf("SomethingWrite"))
        assertFalse(book.isSensitive("Something"))
    }
}
