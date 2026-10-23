package com.apex.loomkit.weave.packager

import com.apex.loomkit.weave.LoomActivePackage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomPackager 测试（织造包——README 承诺的运行时激活通道，此前零覆盖）：
 *
 * - 激活回执即指引全文（模型立即可读）；未知包诚实回执；
 * - 激活集合保序（激活次序 = 提示词段渲染序），重复激活幂等不换位；
 * - 停用 / 清空语义；空目录兜底（未接线时三工具仍可调用）；
 * - 三工具面（loom_package_list/use/drop）+ 坏 JSON 防御折叠。
 */
class LoomPackagerTest {

    private class FakeCatalog : LoomPackageCatalog {
        private val pkgs = linkedMapOf(
            "tdd" to LoomActivePackage("tdd", "TDD 织造包", "先写测试，再写实现；红绿重构循环。"),
            "review" to LoomActivePackage("review", "代码评审包", "提交前自检：可读性/边界/资源释放。")
        )
        override fun list(): List<LoomPackageInfo> =
            pkgs.values.map { LoomPackageInfo(it.id, it.title, "sum-${it.id}") }
        override fun load(id: String): LoomActivePackage? = pkgs[id]
    }

    @Test
    fun activateReceiptCarriesFullInstructions() {
        val p = LoomPackager(FakeCatalog())
        val receipt = p.activate("tdd")
        assertTrue(receipt.contains("已激活织造包「TDD 织造包」"))
        assertTrue("回执必须织入指引全文", receipt.contains("先写测试，再写实现；红绿重构循环。"))
        assertTrue(receipt.contains("请在本会话中遵循"))
    }

    @Test
    fun activateUnknownIdHonestReceipt() {
        val p = LoomPackager(FakeCatalog())
        assertEquals("织造包不存在：nope（用 loom_package_list 查看目录）", p.activate("nope"))
    }

    @Test
    fun activeOrderIsActivationOrderAndIdempotent() {
        val p = LoomPackager(FakeCatalog())
        p.activate("review")
        p.activate("tdd")
        assertEquals(listOf("review", "tdd"), p.activePackages().map { it.id })
        // 重复激活幂等：不新增、不换位（LinkedHashMap 语义）
        p.activate("review")
        assertEquals(listOf("review", "tdd"), p.activePackages().map { it.id })
        assertEquals(2, p.activePackages().size)
    }

    @Test
    fun deactivateAndClearSemantics() {
        val p = LoomPackager(FakeCatalog())
        p.activate("tdd")
        assertEquals("已停用织造包：tdd", p.deactivate("tdd"))
        assertTrue(p.activePackages().isEmpty())
        assertEquals("该包未激活：tdd", p.deactivate("tdd"))
        p.activate("tdd")
        p.clear()
        assertTrue(p.activePackages().isEmpty())
    }

    @Test
    fun emptyCatalogFallback() {
        val p = LoomPackager(LoomPackageCatalog.empty())
        assertTrue(LoomPackageCatalog.empty().list().isEmpty())
        assertEquals("织造包不存在：x（用 loom_package_list 查看目录）", p.activate("x"))
    }

    @Test
    fun toolsSurfaceAndListRendering() = runTest {
        val p = LoomPackager(FakeCatalog())
        val tools = p.tools()
        assertEquals(listOf("loom_package_list", "loom_package_use", "loom_package_drop"), tools.map { it.id })
        assertTrue(tools.all { it.description.isNotBlank() && it.parametersSchema.isNotBlank() })

        val listText = tools.first { it.id == "loom_package_list" }.execute("{}")
        assertEquals("- tdd：TDD 织造包——sum-tdd\n- review：代码评审包——sum-review", listText)

        val emptyListText = LoomPackager(LoomPackageCatalog.empty()).tools()
            .first { it.id == "loom_package_list" }.execute("{}")
        assertEquals("（织造包目录为空或未接线）", emptyListText)
    }

    @Test
    fun useAndDropToolArgumentsDefense() = runTest {
        val p = LoomPackager(FakeCatalog())
        val use = p.tools().first { it.id == "loom_package_use" }
        val drop = p.tools().first { it.id == "loom_package_drop" }

        // 正常路径：use 织入回执 + 落激活集合
        val receipt = use.execute("{\"id\":\"tdd\"}")
        assertTrue(receipt.contains("先写测试，再写实现；红绿重构循环。"))
        assertEquals(listOf("tdd"), p.activePackages().map { it.id })

        // 坏 JSON / 缺参 / 空白 id 全部折叠为「参数缺失」（防御式 IO，不抛异常）
        assertEquals("参数缺失：id", use.execute("not-json"))
        assertEquals("参数缺失：id", use.execute("{}"))
        assertEquals("参数缺失：id", use.execute("{\"id\":\"  \"}"))

        // drop 正常 + 未知
        assertEquals("已停用织造包：tdd", drop.execute("{\"id\":\"tdd\"}"))
        assertEquals("该包未激活：tdd", drop.execute("{\"id\":\"tdd\"}"))
        assertEquals("参数缺失：id", drop.execute("{\"other\":1}"))
    }
}
