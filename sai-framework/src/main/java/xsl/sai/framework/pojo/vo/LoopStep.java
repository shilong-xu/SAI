package xsl.sai.framework.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 自驱动 Agent Loop 的每一步流式事件。
 *
 * <p>后端（{@code AgentAI.processLoop}）把 AgentScope Harness 的 ReAct 循环事件
 * （THINKING_BLOCK / TOOL_CALL / TOOL_RESULT / TEXT_BLOCK 等）映射成本对象的实例，
 * 经 SSE（{@code /agent/orchestrate}）逐条推给前端；前端按 {@code step} 分组渲染
 * 「推理 → 调工具 → 观察」的循环过程，并把 {@code ANSWER} 实时拼接成最终回复。</p>
 *
 * <p>各 type 含义：
 * <ul>
 *   <li>THINK        —— 本轮推理（思考）文本增量，{@code step} 为迭代编号</li>
 *   <li>ACTION       —— 工具调用：{@code tool} 为工具名，{@code args} 为参数(JSON)。
 *                       同一工具先发 tool 名、再发 args 全文（二者分两条，前端合并）</li>
 *   <li>OBSERVATION  —— 工具结果（观察）文本增量；末条带 {@code state}
 *                       （success/error/interrupted/denied）表示终态</li>
 *   <li>ANSWER       —— 最终回答文本增量，{@code step}=0，前端拼入消息气泡</li>
 *   <li>DONE         —— 循环正常结束</li>
 *   <li>MAX_ITERS    —— 达到最大迭代次数仍未产出答案</li>
 *   <li>HINT         —— 框架提示信息</li>
 *   <li>ERROR        —— 循环异常</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoopStep {

    /** 步骤类型（见类注释） */
    private String type;

    /** 1-based 迭代编号；ANSWER/DONE/MAX_ITERS/HINT/ERROR 统一为 0 */
    private int step;

    /** ACTION：工具名 */
    private String tool;

    /** ACTION：工具参数（JSON 字符串） */
    private String args;

    /** THINK/OBSERVATION/ANSWER/HINT/ERROR 的文本增量或全文 */
    private String content;

    /** OBSERVATION 终态：success / error / interrupted / denied */
    private String state;

    public static LoopStep thinking(int step, String delta) {
        return LoopStep.builder().type("THINK").step(step).content(delta).build();
    }

    public static LoopStep action(int step, String tool, String args) {
        return LoopStep.builder().type("ACTION").step(step).tool(tool).args(args).build();
    }

    public static LoopStep observation(int step, String delta) {
        return LoopStep.builder().type("OBSERVATION").step(step).content(delta).build();
    }

    public static LoopStep observationEnd(int step, String state) {
        return LoopStep.builder().type("OBSERVATION").step(step).state(state).build();
    }

    public static LoopStep answer(String delta) {
        return LoopStep.builder().type("ANSWER").step(0).content(delta).build();
    }

    public static LoopStep done() {
        return LoopStep.builder().type("DONE").step(0).build();
    }

    public static LoopStep maxIters() {
        return LoopStep.builder().type("MAX_ITERS").step(0).build();
    }

    public static LoopStep hint(String text) {
        return LoopStep.builder().type("HINT").step(0).content(text).build();
    }

    public static LoopStep error(String msg) {
        return LoopStep.builder().type("ERROR").step(0).content(msg).build();
    }
}
