# apex-loom-kit

**Pure-Kotlin weaving agent-loop engine**（`com.apex.loomkit:loom-weave`）——
移动端 Coding 工作区的第三条思考逻辑线「织造」的循环内核。零 Android 依赖，
任意 Kotlin JVM 宿主可消费。

> 与宿主 [Ultra-Guru/Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent)
> 的深潜线（自研七档）/ 标准线（apex-standard-kit 任务循环）并列；
> 宿主经 [LoomKitAdapter] 以端口-适配器方式接入，三线在 Coding 模式内
> 任意切换。

## 织造范式（任务完成 = 经纬交织）

| 隐喻 | 子系统 | 内容 |
|---|---|---|
| **思考链为经线** | deliberate 块 + 原生 reasoning 双通道 | 每轮先思考再行动；纯思考回合退回重织（nudge 催促） |
| **工具调用为纬线** | in-band 标记协议 | `use` 调用块 / `outcome_` 随机短码回执 / 实体转义 / 预算三级裁剪；原生函数调用由桥接层翻译成同一协议（无函数调用能力的模型全兼容） |
| **记忆为布面** | digest 摘要织入压缩 | SUMMARY 角色摘要 + 滑窗兜底，压缩状态全程可见 |
| **循环为梭** | 流式回合机 | 串行批次执行 / 防环三警四收敛 / 瞬时故障阶梯重试 / 输出截断续跑 / 运行中转向队列 |

### 子系统一览

- **标记协议**：文法常量 + 六态流式增量解析器（跨块 holdback）+ 原生调用
  桥接 + 回执格式化器；
- **权限门**：OBSERVE / FABRIC / UNBOUND 三模态 × READ / WRITE 效应 ×
  工作区边界路径校验 × 会话级批准记忆 × 命令前缀粒度 × 敏感恒问；
- **子线程群（swarm）**：`loom_thread_start / send / status / observe`
  agent-as-tools 委派 + 并发 / 回合 / 输出三预算；
- **织造包**：技能包运行时激活——指引织入回执 + 系统提示词持续生效；
- **PLAN 门**：`loom-plan` 布样人控门（确认 / 驳回 / 重织）；
- **思考策略**：档位 × 模型家族的三重表达（回合预算倍率 / 努力取向文案 /
  原生推理偏好）。

## 宿主接入（端口-适配器）

库内核只依赖 `LoomPorts` 声明的端口（模型客户端 / 工具面 / 工具执行 /
记忆 / 日志 / 失败分类 / token 估算 / 环境信息 / 模型信息 / 工作区上下文段），
宿主实现端口即可接入——事件协议（`WeaveEvent`）与消息协议（`WeaveMessage`）
为库自有类型，与宿主 UI 协议逐型映射（宿主侧 `LoomKitAdapter` 十行一级薄适配）。

```kotlin
val engine = LoomEngine(
    modelClient = ...,          // 端口：流式/非流式 LLM 调用
    toolSource = ...,           // 端口：工具定义清单
    toolRunner = ...,           // 端口：工具流式执行
    memory = ...,               // 端口：会话持久化（null = 纯内存）
    permissionSource = ...,     // 端口：权限快照
    engineConfig = LoomEngineConfig()
)
engine.execute(WeaveUserInput.text("任务")) // Flow<WeaveEvent>
```

## 构建

```bash
./gradlew :loom-weave:test        # 纯 JVM 测试（零 Android 依赖证明）
./gradlew publishToMavenLocal     # 可发布性验证
```

## 兼容矩阵

| 本库 | Kotlin | Gradle wrapper | 宿主 |
|---|---|---|---|
| 1.0.x | 2.2.21 | 9.8.1 | Android-Guru-Agent（composite build 按坐标替换） |

composite build 要求两侧工具链兼容（同 apex-standard-kit 纪律）：宿主
升级 Kotlin / Gradle 后本库必须同步跟进。

## CI

- **CI**：`build-test`（编译 + 纯 JVM 测试 + mavenLocal 冒烟）+
  `consumer-check`（workflow_dispatch —— 宿主织造线接线合并后对宿主
  `:app:compileDebugKotlin` 跑跨仓验证）；
- **Guard Rails**：词汇合规零容忍（上游项目词汇任何形态不得出现——本库
  为改名衍生）+ 公共 API 面快照；
- **Notify Host**：main 全绿 → `loom-kit-updated` repository_dispatch →
  宿主 `sync-loom-kit` 即时 bump lock（缺 PAT 时宿主 30 分钟轮询兜底）。

## 许可

MIT（见 [LICENSE](LICENSE)）。
