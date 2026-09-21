package xsl.sai.client.manager;

import org.reactivestreams.Subscription;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 维护「会话 ID -> 当前 SSE 流式订阅」的映射，用于支持用户在前端中途打断（停止）智能体思考。
 *
 * <p>为什么不用 AgentScope 的 HarnessAgent.interrupt()：
 * HarnessAgent 在本项目是 Spring 单例，且仅暴露了无会话参数的 interrupt() / interrupt(Msg)，
 * 底层会走 ReActAgent 的共享字段 defaultSessionId。在并发多会话下，直接调用 interrupt()
 * 无法精确指向目标会话（受 defaultSessionId 竞态影响），语义不可靠。
 *
 * <p>因此这里以 Reactor 的订阅取消（Subscription.cancel）作为确定性的打断机制：
 * 每个会话的 SSE 订阅在 doOnSubscribe 时登记，stop 时调用 cancel，天然按会话隔离。
 */
@Component
public class ChatStreamRegistry {

    private final ConcurrentMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public void register(String conversationId, Subscription subscription) {
        subscriptions.put(conversationId, subscription);
    }

    public void unregister(String conversationId, Subscription subscription) {
        // 仅当当前登记的订阅就是本次的订阅时才移除，避免「停止后立即重发同一会话」时，
        // 旧请求的 doFinally 把新请求的订阅登记误删，导致新会话无法再被停止
        subscriptions.remove(conversationId, subscription);
    }

    /**
     * 取消指定会话的流式订阅，从而打断正在进行的思考/输出。
     *
     * @return 是否真的存在活跃流被取消
     */
    public boolean cancel(String conversationId) {
        Subscription subscription = subscriptions.get(conversationId);
        if (subscription != null) {
            subscription.cancel();
            return true;
        }
        return false;
    }
}
