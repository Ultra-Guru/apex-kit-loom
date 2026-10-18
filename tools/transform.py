#!/usr/bin/env python3
"""Transform guru-agent loom sources into apex-loom-kit library sources.

Mechanical clean-room relocation: package rename + host-type inversion into
library-owned Weave* contracts. Behavior-preserving; logic untouched.

Run once at extraction time; kept for provenance (not part of the shipped
module).
"""
import re
from pathlib import Path

SRC = Path("/home/z/guru-agent/core/code-engine/src")
DST = Path("/home/z/apex-loom-kit/loom-weave/src")

# ── Fully-qualified name rewrites (imports AND inline FQNs) ──────────────
FQN_RULES = [
    ("package com.apex.agent.core.code.loom\n", "package com.apex.loomkit.weave\n"),
    ("com.apex.agent.core.code.loom.", "com.apex.loomkit.weave."),
    ("com.apex.agent.core.llm.LlmStreamChunk", "com.apex.loomkit.weave.WeaveStreamChunk"),
    ("com.apex.agent.core.llm.LlmResponse", "com.apex.loomkit.weave.WeaveModelResponse"),
    ("com.apex.agent.core.llm.LlmMessage", "com.apex.loomkit.weave.WeaveMessage"),
    ("com.apex.agent.core.llm.ToolDefinition", "com.apex.loomkit.weave.WeaveToolDefinition"),
    ("com.apex.agent.core.llm.ToolCall", "com.apex.loomkit.weave.WeaveToolCall"),
    ("com.apex.agent.core.llm.ImageContent", "com.apex.loomkit.weave.WeaveImageContent"),
    ("com.apex.agent.core.llm.Usage", "com.apex.loomkit.weave.WeaveUsage"),
    ("com.apex.agent.core.engine.AgentEvent", "com.apex.loomkit.weave.WeaveEvent"),
    ("com.apex.agent.core.engine.UserInput", "com.apex.loomkit.weave.WeaveUserInput"),
    ("com.apex.agent.core.engine.AgentMode", "com.apex.loomkit.weave.WeaveMode"),
    ("com.apex.agent.core.engine.InputType", "com.apex.loomkit.weave.WeaveInputType"),
    ("com.apex.agent.core.engine.FileRef", "com.apex.loomkit.weave.WeaveFileRef"),
    ("com.apex.agent.core.engine.ExecutionPlan", "com.apex.loomkit.weave.WeaveExecutionPlan"),
    ("com.apex.agent.core.engine.PlanStep", "com.apex.loomkit.weave.WeavePlanStep"),
    ("com.apex.agent.core.engine.RiskLevel", "com.apex.loomkit.weave.WeaveRiskLevel"),
    ("com.apex.agent.core.tools.ToolStreamEvent", "com.apex.loomkit.weave.WeaveToolStream"),
    ("com.apex.agent.core.code.thinking.CodeThinkingLevel", "com.apex.loomkit.weave.WeaveThinkingLevel"),
]

# ── Word-boundary identifier renames (applied after FQN pass) ─────────────
IDENT_RULES = [
    ("LlmStreamChunk", "WeaveStreamChunk"),
    ("LlmResponse", "WeaveModelResponse"),
    ("LlmMessage", "WeaveMessage"),
    ("ToolDefinition", "WeaveToolDefinition"),
    ("ToolStreamEvent", "WeaveToolStream"),
    ("ToolCall", "WeaveToolCall"),
    ("AgentEvent", "WeaveEvent"),
    ("UserInput", "WeaveUserInput"),
    ("AgentMode", "WeaveMode"),
    ("InputType", "WeaveInputType"),
    ("CodeThinkingLevel", "WeaveThinkingLevel"),
    ("ImageContent", "WeaveImageContent"),
    ("ExecutionPlan", "WeaveExecutionPlan"),
    ("PlanStep", "WeavePlanStep"),
    ("RiskLevel", "WeaveRiskLevel"),
    ("FileRef", "WeaveFileRef"),
]

# Bridge inner class rename (avoid collision with WeaveUsage constructor)
BRIDGE_RENAMES = [
    ("LoomWireFrame.Usage", "LoomWireFrame.UsageFrame"),
    ("data class Usage(", "data class UsageFrame("),
]

# Runner special case: drop host fold call (adapter side responsibility now)
SPECIAL = [
    ("session.thinkingLevel().toAgentLevel()", "session.thinkingLevel()"),
]

def transform(text: str, is_test: bool) -> str:
    for old, new in FQN_RULES:
        text = text.replace(old, new)
    for old, new in BRIDGE_RENAMES:
        text = text.replace(old, new)
    for old, new in SPECIAL:
        text = text.replace(old, new)
    for old, new in IDENT_RULES:
        text = re.sub(r"\b" + old + r"\b", new, text)
    if is_test:
        # bare Usage(...) constructor calls -> WeaveUsage (dot-qualified refs already renamed)
        text = re.sub(r"(?<![.\w])Usage\b", "WeaveUsage", text)
    return text

MAIN_FILES = [
    "LoomEngineConfig.kt", "LoomModels.kt", "LoomPrompts.kt",
    "loop/LoomAbortSignal.kt", "loop/LoomGuards.kt", "loop/LoomPlanGate.kt",
    "loop/LoomPorts.kt", "loop/LoomRoundRunner.kt", "loop/LoomSession.kt",
    "markup/LoomMarkupGrammar.kt", "markup/LoomMarkupParser.kt",
    "markup/LoomNativeBridge.kt", "markup/LoomOutcomeFormatter.kt",
    "memory/LoomMemoryWeaver.kt",
    "packager/LoomPackager.kt",
    "permission/LoomApprovalBox.kt", "permission/LoomPermissionGovernor.kt",
    "permission/LoomPermissionModels.kt", "permission/LoomToolRiskBook.kt",
    "steer/LoomSteering.kt",
    "swarm/LoomCondition.kt", "swarm/LoomSwarmHub.kt", "swarm/LoomSwarmTools.kt",
    "thinking/LoomThinkingPolicy.kt",
]

TEST_FILES = [
    "LoomGuardsSteerApprovalTest.kt", "LoomSessionTest.kt",
    "loop/LoomRoundRunnerTest.kt",
    "markup/LoomMarkupParserTest.kt", "markup/LoomNativeBridgeTest.kt",
    "markup/LoomOutcomeFormatterTest.kt",
    "memory/LoomMemoryWeaverTest.kt",
    "permission/LoomPermissionGovernorTest.kt",
    "swarm/LoomSwarmHubTest.kt",
]

def main() -> None:
    main_root = SRC / "main/kotlin/com/apex/agent/core/code/loom"
    test_root = SRC / "test/kotlin/com/apex/agent/core/code/loom"
    for rel in MAIN_FILES:
        src = main_root / rel
        dst = DST / "main/kotlin/com/apex/loomkit/weave" / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        text = transform(src.read_text(encoding="utf-8"), is_test=False)
        dst.write_text(text, encoding="utf-8")
        print(f"main  {rel}")
    for rel in TEST_FILES:
        src = test_root / rel
        dst = DST / "test/kotlin/com/apex/loomkit/weave" / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        text = transform(src.read_text(encoding="utf-8"), is_test=True)
        dst.write_text(text, encoding="utf-8")
        print(f"test  {rel}")

if __name__ == "__main__":
    main()
