package xsl.sai.agent.orchestration;

import xsl.sai.agent.AgentAI;

/**
 * 核心智能体节点：使用用户「真实会话 ID」（sessionSuffix 为空），
 * 由 HarnessAgent 维护记忆 / 工具 / 技能 / 计划模式；读取上游需求分析与执行计划注入提示。
 */
public class CoreAgentNode extends AgentScopeNode {

    public CoreAgentNode(AgentAI agentAI) {
        super(agentAI,
                """
                        【需求分析】
                        {{analysis}}
                        
                        【执行计划】请严格按以下计划完成用户请求：
                        {{plan}}
                        
                        【用户请求】
                        {{user_input}}""",
                "core_result", "");
    }
}
