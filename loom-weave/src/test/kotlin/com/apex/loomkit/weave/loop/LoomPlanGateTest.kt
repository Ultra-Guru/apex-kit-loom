package com.apex.loomkit.weave.loop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomPlanGate 测试（布样人控门——此前仅默认构造，无行为锁）：
 *
 * - submit/await 确认与驳回双路；迟到裁决返回 false；
 * - 超时按驳回收场（note 携带秒数说明——任务继续、模型重织）；
 * - 单发待决（并发第二挂起立即驳回、note 为 null）；
 * - cancel 抢占：挂起处抛 CancellationException("loom-abort")。
 */
class LoomPlanGateTest {

    @Test
    fun submitBeforeAwaitReturnsFalse() {
        val gate = LoomPlanGate()
        assertFalse(gate.submit(true))
        assertFalse(gate.isAwaiting())
    }

    @Test
    fun confirmPathDeliversVerdict() = runTest {
        val gate = LoomPlanGate()
        val waiter = async { gate.await(10_000) }
        yield()  // 让 waiter 到达挂起点（稳定 API，无 Experimental opt-in）
        assertTrue(gate.isAwaiting())
        assertTrue(gate.submit(true))
        val v = waiter.await()
        assertTrue(v.confirmed)
        assertNull(v.note)
        assertFalse(gate.isAwaiting())
        // 已收场后的迟到投递
        assertFalse(gate.submit(true))
    }

    @Test
    fun rejectPathDeliversVerdictWithoutNote() = runTest {
        val gate = LoomPlanGate()
        val waiter = async { gate.await(10_000) }
        yield()
        assertTrue(gate.submit(false))
        val v = waiter.await()
        assertFalse(v.confirmed)
        assertNull(v.note)
    }

    @Test
    fun timeoutCountsAsRejectWithNote() = runTest {
        val gate = LoomPlanGate()
        // 虚拟时间：2500ms 立即到期；note 秒数 = 2500/1000 = 2
        val v = gate.await(2_500)
        assertFalse(v.confirmed)
        assertEquals("确认窗口超时（2 秒）未响应", v.note)
        assertFalse(gate.isAwaiting())
    }

    @Test
    fun secondAwaitWhilePendingIsImmediateReject() = runTest {
        val gate = LoomPlanGate()
        val waiter = async { gate.await(10_000) }
        yield()
        // 待决期间第二次挂起：CAS 失败 → 立即驳回（note=null，区别于超时）
        val second = gate.await(5_000)
        assertFalse(second.confirmed)
        assertNull(second.note)
        assertTrue(gate.submit(true))
        assertTrue(waiter.await().confirmed)
    }

    @Test
    fun cancelPreemptsWithCancellationException() = runTest {
        val gate = LoomPlanGate()
        val ferry = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                gate.await(10_000)
                ferry.complete(null)
            } catch (t: Throwable) {
                ferry.complete(t)
            }
        }
        kotlinx.coroutines.yield()
        assertTrue(gate.isAwaiting())
        gate.cancel()
        val thrown = ferry.await()
        assertTrue("期待 CancellationException，实际 $thrown", thrown is CancellationException)
        assertEquals("loom-abort", (thrown as CancellationException).message)
        assertFalse(gate.isAwaiting())
        job.cancel()
    }
}
