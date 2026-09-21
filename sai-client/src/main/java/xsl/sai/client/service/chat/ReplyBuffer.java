package xsl.sai.client.service.chat;

/**
 * 单轮对话的回复累积器：
 * <ul>
 *   <li>{@code answer}   —— 最终气泡答案（落库首选），由子类在流式过程中按需追加；</li>
 *   <li>{@code fallback} —— 中断兜底文本（如 SAA 未跑到总结节点时，回退到各步已产出的文本）。</li>
 * </ul>
 * 以「按请求创建的局部对象」方式在策略间传递，避免策略 Bean 的实例字段在并发请求之间串数据。
 */
public class ReplyBuffer {
    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder fallback = new StringBuilder();

    public void appendAnswer(CharSequence s) {
        if (s != null) answer.append(s);
    }

    public void appendFallback(CharSequence s) {
        if (s != null) fallback.append(s);
    }

    public String answer() {
        return answer.toString();
    }

    public String fallback() {
        return fallback.toString();
    }
}
