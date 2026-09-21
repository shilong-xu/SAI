package xsl.sai.client.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import xsl.sai.client.service.NotifyService;
import xsl.sai.framework.base.BaseController;
import xsl.sai.framework.holder.UserHolder;
import xsl.sai.framework.result.Result;

import java.util.Map;

/**
 * 站内消息接口（需登录，全部按当前登录用户隔离）
 *
 * @author SAI
 */
@Slf4j
@RestController
@RequestMapping("/api/notify")
public class NotifyController extends BaseController {

    private final NotifyService notifyService;

    public NotifyController(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    /** 消息列表（未读优先，再按时间倒序） */
    @GetMapping("/list")
    public Mono<Result> list(@RequestParam(defaultValue = "20") int limit) {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            return notifyService.list(userId, limit).collectList()
                    .map(list -> Result.success(Map.of("list", list)))
                    .onErrorResume(e -> Mono.just(Result.error(500, "消息列表查询失败：" + e.getMessage())));
        });
    }

    /** 未读数量 */
    @GetMapping("/unread-count")
    public Mono<Result> unreadCount() {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            return notifyService.unreadCount(userId)
                    .map(count -> Result.success(Map.of("count", count)))
                    .onErrorResume(e -> Mono.just(Result.error(500, "未读数查询失败：" + e.getMessage())));
        });
    }

    /**
     * 增量同步：页面刷新 / SSE 重连后调用，补偿断线窗口内错过的推送。
     * 返回 { count: 当前未读数, missed: afterId 之后的新消息（最新在前，最多 20 条） }
     */
    @GetMapping("/sync")
    public Mono<Result> sync(@RequestParam(defaultValue = "0") long afterId) {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            return notifyService.sync(userId, afterId)
                    .map(Result::success)
                    .onErrorResume(e -> Mono.just(Result.error(500, "消息增量同步失败：" + e.getMessage())));
        });
    }

    /** 单条已读 */
    @PostMapping("/read/{id}")
    public Mono<Result> read(@PathVariable Long id) {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            return notifyService.read(userId, id)
                    .map(ok -> ok ? Result.success(true) : Result.error(404, "消息不存在或已读过"))
                    .onErrorResume(e -> Mono.just(Result.error(500, "标记已读失败：" + e.getMessage())));
        });
    }

    /** 全部已读 */
    @PostMapping("/read-all")
    public Mono<Result> readAll() {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            return notifyService.readAll(userId)
                    .map(count -> Result.success(Map.of("count", count)))
                    .onErrorResume(e -> Mono.just(Result.error(500, "全部已读失败：" + e.getMessage())));
        });
    }

    /**
     * 自测：给当前用户发一条消息，用于验证「落库 + SSE 推送」整条链路是否打通。
     * （推送内容正式确定后可删除本接口）
     */
    @PostMapping("/test")
    public Mono<Result> test(@RequestParam(required = false) String title,
                             @RequestParam(required = false) String content) {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            return notifyService.push(userId, "info",
                            title == null || title.isBlank() ? "测试消息" : title,
                            content == null || content.isBlank() ? "这是一条用于验证推送链路的测试消息" : content,
                            null)
                    .map(Result::success)
                    .onErrorResume(e -> Mono.just(Result.error(500, "测试消息发送失败：" + e.getMessage())));
        });
    }
}
