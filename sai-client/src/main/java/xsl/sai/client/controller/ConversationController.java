package xsl.sai.client.controller;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.dto.CreateConversationDTO;
import xsl.sai.client.pojo.dto.UpdateConversationDTO;
import xsl.sai.client.service.ConversationService;
import xsl.sai.client.service.MessageService;
import xsl.sai.framework.base.BaseController;
import xsl.sai.framework.holder.UserHolder;
import xsl.sai.framework.result.Result;

/**
 * 会话管理接口（需登录）
 *
 * @author SAI
 */
@Slf4j
@RestController
@RequestMapping("/api/conversation")
public class ConversationController extends BaseController {

    private final ConversationService conversationService;
    private final MessageService messageService;

    public ConversationController(ConversationService conversationService, MessageService messageService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
    }

    /** 会话列表 */
    @GetMapping("/list")
    public Mono<Result> list() {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            if (StrUtil.isBlank(userId)) {
                return Mono.just(Result.error(401, "请先登录"));
            }
            return conversationService.listByUser(userId).map(Result::success);
        });
    }

    /** 新建会话 */
    @PostMapping
    public Mono<Result> create(@RequestBody(required = false) CreateConversationDTO dto) {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            if (StrUtil.isBlank(userId)) {
                return Mono.just(Result.error(401, "请先登录"));
            }
            String title = dto == null ? null : dto.getTitle();
            String model = dto == null ? null : dto.getModel();
            return conversationService.create(userId, title, model).map(Result::success);
        });
    }

    /** 会话消息记录 */
    @GetMapping("/{id}/messages")
    public Mono<Result> messages(@PathVariable("id") String id) {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            if (StrUtil.isBlank(userId)) {
                return Mono.just(Result.error(401, "请先登录"));
            }
            return messageService.listByConversation(id, userId).map(Result::success);
        });
    }

    /** 改名 */
    @PutMapping("/rename")
    public Mono<Result> rename(@RequestBody UpdateConversationDTO dto) {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            if (StrUtil.isBlank(userId)) {
                return Mono.just(Result.error(401, "请先登录"));
            }
            if (dto == null || StrUtil.isBlank(dto.getId()) || StrUtil.isBlank(dto.getTitle())) {
                return Mono.just(Result.error(400, "会话ID与标题均不能为空"));
            }
            return conversationService.rename(dto.getId(), dto.getTitle())
                    .then(Mono.just(Result.success("ok")));
        });
    }

    /** 置顶 / 取消置顶 */
    @PutMapping("/pin")
    public Mono<Result> pin(@RequestBody UpdateConversationDTO dto) {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            if (StrUtil.isBlank(userId)) {
                return Mono.just(Result.error(401, "请先登录"));
            }
            if (dto == null || StrUtil.isBlank(dto.getId()) || dto.getIsPinned() == null) {
                return Mono.just(Result.error(400, "会话ID与置顶状态均不能为空"));
            }
            return conversationService.pin(dto.getId(), dto.getIsPinned())
                    .then(Mono.just(Result.success("ok")));
        });
    }

    /** 删除（软删） */
    @DeleteMapping("/{id}")
    public Mono<Result> delete(@PathVariable("id") String id) {
        return Mono.deferContextual(view -> {
            String userId = UserHolder.getUserId(view);
            if (StrUtil.isBlank(userId)) {
                return Mono.just(Result.error(401, "请先登录"));
            }
            return conversationService.softDelete(id, userId)
                    .then(Mono.just(Result.success("ok")));
        });
    }
}
