package com.apex.loomkit.weave

import com.apex.loomkit.weave.loop.LoomAbortSignal
import com.apex.loomkit.weave.loop.LoomEnvironmentInfo
import com.apex.loomkit.weave.loop.LoomEnvironmentSource
import com.apex.loomkit.weave.loop.LoomFailureClassifier
import com.apex.loomkit.weave.loop.LoomFailureKind
import com.apex.loomkit.weave.loop.LoomLogger
import com.apex.loomkit.weave.loop.LoomMemory
import com.apex.loomkit.weave.loop.LoomModelClient
import com.apex.loomkit.weave.loop.LoomModelInfoSource
import com.apex.loomkit.weave.loop.LoomPlanGate
import com.apex.loomkit.weave.loop.LoomRoundRunner
import com.apex.loomkit.weave.loop.LoomSession
import com.apex.loomkit.weave.loop.LoomTokenEstimator
import com.apex.loomkit.weave.loop.LoomToolRunner
import com.apex.loomkit.weave.loop.LoomToolSource
import com.apex.loomkit.weave.loop.LoomWorkspaceContextSource
import com.apex.loomkit.weave.markup.LoomOutcomeFormatter
import com.apex.loomkit.weave.memory.LoomMemoryWeaver
import com.apex.loomkit.weave.packager.LoomPackageCatalog
import com.apex.loomkit.weave.packager.LoomPackager
import com.apex.loomkit.weave.permission.LoomApprovalBox
import com.apex.loomkit.weave.permission.LoomPermissionGovernor
import com.apex.loomkit.weave.permission.LoomPermissionSource
import com.apex.loomkit.weave.steer.LoomSteering
import com.apex.loomkit.weave.swarm.LoomLocalTool
import com.apex.loomkit.weave.swarm.LoomSwarmHub
import com.apex.loomkit.weave.swarm.LoomSwarmTools
import com.apex.loomkit.weave.thinking.LoomThinkingPolicy
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * # Loom Engine — 织造线引擎门面（库内核组装点）
 *
 * 织造循环的**组装与门面**：循环语义全部在织造自有组件
 * （[LoomRoundRunner] 梭回合机 / [LoomSession] 会话织物 / 权限治理 /
 * 子线程群 / 织造包），本类只做三件事：
 *
 * 1. **子系统组装**：把端口（模型客户端 / 工具面 / 记忆 / 日志…）接进
 *    各循环组件；
 * 2. **工具面组装**：宿主工具目录 + 引擎本地工具（`loom_thread_*` 子线程
 *    四件 + `loom_package_*` 织造包三件）经 forcedTools 过滤；
 * 3. **门面契约**：执行 / 中止 / 问答投递 / 工作区与易变段更新 / 记忆
 *    生命周期——宿主适配器逐方法直通。
 *
 * ```
 * 宿主适配器 ─► LoomEngine（本类）
 *                ├─ LoomSession（会话织物 + 提示词 JIT）
 *                ├─ LoomRoundRunner（梭回合机）
 *                ├─ LoomPermissionGovernor（权限三态门）
 *                ├─ LoomSwarmHub（子线程群）
 *                └─ LoomPackager（织造包）
 * ```
 *
 * ## 执行器纪律（宿主侧）
 *
 * 织造线自带权限门 → 注入的 [hostToolRunner] 应为宿主的**仅环境门**
 * 执行器——共用宿主主执行器的组合门会双弹窗（权限问答归本引擎独占）。
 */
class LoomEngine(
    /** 模型客户端端口（主循环 / digest / 子线程共用）。 */
    private val modelClient: LoomModelClient,
    /** 宿主工具目录源（注册表快照端口）。 */
    private val hostToolSource: LoomToolSource,
    /** 宿主工具执行端口（应为仅环境门执行器——防双弹窗）。 */
    private val hostToolRunner: LoomToolRunner,
    /** 会话记忆端口（null = 子线程/测试：纯内存会话）。 */
    private val memory: LoomMemory? = null,
    /** 权限快照源（设置层实时通道；缺省纯模态兜底）。 */
    private val permissionSource: LoomPermissionSource = LoomPermissionSource.NONE,
    /** 模型信息源（真实上下文窗口）。 */
    private val modelInfoSource: LoomModelInfoSource? = null,
    /** 宿主提示词段源（工作区 / 全局规则 / 项目规则三段）。 */
    private val workspaceContextSource: LoomWorkspaceContextSource? = null,
    /** 日志端口（缺省静默）。 */
    private val logger: LoomLogger = LoomLogger.Silent,
    /** token 估算端口（缺省启发式）。 */
    private val estimator: LoomTokenEstimator = LoomHeuristicEstimator,
    /** 失败分类器端口（缺省文本嗅探启发）。 */
    private val failureClassifier: LoomFailureClassifier = LoomFailureClassifier.Default,
    /** 织造包目录端口（宿主技能仓库接线；缺省空目录）。 */
    private val packageCatalog: LoomPackageCatalog = LoomPackageCatalog.empty(),
    /** 引擎预算配置（构造期统一 sanitized 钳位）。 */
    engineConfig: LoomEngineConfig = LoomEngineConfig()
) {

    private val config = engineConfig.sanitized()

    // ═══════════════════════════ 子系统组装 ═══════════════════════════

    private val packager = LoomPackager(packageCatalog)

    /** 环境源（声明先于 swarmHub——构造期即引用；显式类型注解打断与 session 的推断环）。 */
    private val environmentSource: LoomEnvironmentSource = LoomEnvironmentSource {
        LoomEnvironmentInfo(
            platformText = PLATFORM_TEXT,
            workdirLabel = session.currentWorkspaceRoot()?.path,
            isGitRepo = session.currentWorkspaceRoot()
                ?.let { File(it, ".git").exists() }
        )
    }

    private val swarmHub = LoomSwarmHub(
        config = config,
        modelClient = modelClient,
        parentToolSource = hostToolSource,
        toolRunner = LocalRoutingRunner(),
        permissionSource = permissionSource,
        failureClassifier = failureClassifier,
        logger = logger,
        environmentSource = environmentSource,
        workspaceRootProvider = { session.currentWorkspaceRoot() }
    )

    /** 引擎本地工具（子线程四件 + 织造包三件）。 */
    private val localTools: List<LoomLocalTool> =
        LoomSwarmTools(swarmHub).all() + packager.tools()

    /**
     * 本地工具路由表——lazy 打断类型推断环（LocalRoutingRunner 的方法体
     * 引用本表，而 swarmHub 装配在先；惰性求值后各声明类型自洽）。
     */
    private val localToolById: Map<String, LoomLocalTool> by lazy {
        localTools.associateBy { tool: LoomLocalTool -> tool.id }
    }

    /** 工具面（宿主目录 + 本地工具；forcedTools 过滤）。 */
    private val compositeToolSource = LoomToolSource { visibleToolSurface() }

    private val session: LoomSession = LoomSession(
        config = config,
        memory = memory,
        estimator = estimator,
        environmentSource = environmentSource,
        workspaceContextSource = workspaceContextSource,
        modelInfoSource = modelInfoSource,
        logger = logger
    )

    private val governor = LoomPermissionGovernor(permissionSource)
    private val approvalBox = LoomApprovalBox()
    private val steering = LoomSteering(config)
    private val planGate = LoomPlanGate()
    private val weaver = LoomMemoryWeaver(config, modelClient, estimator, logger)
    private val abortSignal = LoomAbortSignal()

    private val runner = LoomRoundRunner(
        config = config,
        session = session,
        modelClient = modelClient,
        toolSource = compositeToolSource,
        toolRunner = LocalRoutingRunner(),
        governor = governor,
        weaver = weaver,
        steering = steering,
        approvalBox = approvalBox,
        planGate = planGate,
        formatter = LoomOutcomeFormatter(config),
        thinkingPolicy = LoomThinkingPolicy(),
        failureClassifier = failureClassifier,
        logger = logger,
        abortSignal = abortSignal,
        modelInfoSource = modelInfoSource
    )

    /** forcedTools 面（渐进披露）。 */
    @Volatile
    private var forcedToolIds: Set<String> = emptySet()

    @Volatile
    private var exposeAllTools: Boolean = true

    // ═══════════════════════════ 执行门面 ═══════════════════════════

    /** 主循环（冷流）：一次用户输入 → 织到完成。 */
    fun execute(input: String): Flow<WeaveEvent> =
        execute(WeaveUserInput.text(input))

    fun execute(input: WeaveUserInput): Flow<WeaveEvent> = runner.run(input)

    /** 任务中止请求（协作式——回合边界与流帧边界收口）。 */
    suspend fun abort() {
        runner.requestAbort()
    }

    /** 权限问答回答投递。 */
    fun submitUserInput(answer: String) {
        runner.submitApprovalAnswer(answer)
    }

    /** 问答窗口抢占取消（挂起问题按未决收束）。 */
    fun cancelUserInput() {
        approvalBox.cancelPending()
    }

    /** 运行中转向（未运行返回 false——调用方改走常规发送）。 */
    fun pushSteer(text: String): Boolean = runner.offerSteering(text)

    // ═══════════════════════════ 易变段门面 ═══════════════════════════

    /** 绑定工作区（记忆通道切换 + 历史重放）。 */
    fun setActiveWorkspace(
        workspaceId: String,
        name: String,
        root: File?,
        activeFile: String?
    ) {
        session.bindWorkspace(workspaceId, name, root, activeFile)
    }

    /** 活动文件更新（提示词段消费）。 */
    fun setActiveFile(activeFile: String?) {
        session.updateActiveFile(activeFile)
    }

    /** 任务前预热（全 JIT 架构——无预热可做）。 */
    fun prepareForTask() {
        // 提示词每回合重织 / 权限快照每调用拉取——空实现即语义
    }

    /** 全局规则更新。 */
    fun updateGlobalRules(rules: String) {
        session.updateGlobalRules(rules)
    }

    /** 会话模态更新（PLAN 档触发布样人控门）。 */
    fun updateMode(mode: WeaveMode) {
        session.updateMode(mode)
    }

    /** 布样裁决投递（无挂起等待返回 false）。 */
    fun submitPlanConfirmation(confirmed: Boolean): Boolean =
        runner.submitPlanConfirmation(confirmed)

    /** 附加段更新。 */
    fun updateSessionExtras(extras: String?) {
        session.updateSessionExtras(extras)
    }

    /** 人设更新。 */
    fun updateRolePersona(roleDefinition: String, rolePrompt: String?) {
        session.updateRolePersona(roleDefinition, rolePrompt)
    }

    /** forcedTools 面更新（非空 = 只暴露选中；exposeAll = 全量兜底）。 */
    fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) {
        this.forcedToolIds = forcedToolIds
        this.exposeAllTools = exposeAll
    }

    /** 思考档位（回合预算倍率 / 努力取向文案的消费源）。 */
    fun updateThinkingLevel(level: WeaveThinkingLevel) {
        session.updateThinkingLevel(level)
    }

    /** 当前思考档位。 */
    fun thinkingLevel(): WeaveThinkingLevel = session.thinkingLevel()

    /** 档位决策文案（织造线无 AUTO 预检——恒 null）。 */
    fun currentThinkingDecision(): String? = null

    /** 清空会话（记忆通道 / 批准记忆 / 织造包 / 子线程群全联动）。 */
    fun clearConversation() {
        session.clear()
        governor.clearApprovals()
        packager.clear()
        session.updateActivePackages(emptyList())
        swarmHub.shutdownAll()
        steering.clear()
    }

    /** 历史轮数（不含系统轮）。 */
    fun historyCount(): Int = session.historyCount()

    /** 工作区快照（无绑定返回 null）。 */
    fun currentWorkspace(): WorkspaceInfo? {
        val root = session.currentWorkspaceRoot() ?: return null
        return WorkspaceInfo(
            session.currentWorkspaceId(),
            session.currentWorkspaceName(),
            root
        )
    }

    /** 当前上下文 token 估算（含系统轮近似）。 */
    fun currentTokenCount(): Int =
        session.estimateContextTokens(visibleToolSurface())

    /** 上下文窗口预算。 */
    fun maxContextTokens(): Int = config.maxContextTokens

    // ═══════════════════════════ 工具面组装 ═══════════════════════════

    /** 可见工具面（forcedTools 非空 = 只暴露选中）。 */
    private fun visibleToolSurface(): List<WeaveToolDefinition> {
        val hostDefs = hostToolSource.definitions()
        val localDefs = localTools.map {
            WeaveToolDefinition(
                name = it.id,
                description = it.description,
                parameters = it.parametersSchema
            )
        }
        val combined = hostDefs + localDefs
        return if (forcedToolIds.isEmpty() || exposeAllTools) {
            combined
        } else {
            combined.filter { forcedToolIds.contains(it.name) }
        }
    }

    // ═══════════════════════════ 本地路由 ═══════════════════════════

    /**
     * 本地路由执行器：`loom_*` 引擎本地直执（执行后同步织造包激活态到
     * 会话——提示词段每回合 JIT 消费），其余透传宿主执行器。
     */
    private inner class LocalRoutingRunner : LoomToolRunner {
        override fun executeStream(toolId: String, arguments: String): Flow<WeaveToolStream> {
            val local = localToolById[toolId] ?: return hostToolRunner.executeStream(toolId, arguments)
            return flow {
                val output = runCatching { local.execute(arguments) }
                    .getOrElse { t ->
                        logger.warn(TAG, "local tool $toolId faulted: ${t.message}")
                        "本地工具执行失败：${t.message ?: t::class.simpleName}"
                    }
                // 织造包工具执行后激活集合可能变化——同步进会话（提示词段 JIT）
                session.updateActivePackages(packager.activePackages())
                emit(WeaveToolStream.Output(output))
                emit(WeaveToolStream.Complete(output))
            }
        }
    }

    /** 工作区快照（库自有口径——宿主适配器映射到自家 UI 模型）。 */
    data class WorkspaceInfo(
        val id: String,
        val name: String,
        val root: File
    )

    companion object {
        private const val TAG = "LoomEngine"

        /** env 块的平台行（宿主适配器可经环境源覆盖工作目录/git 探测）。 */
        private const val PLATFORM_TEXT = "Android with a PRoot Ubuntu sandbox"
    }
}
