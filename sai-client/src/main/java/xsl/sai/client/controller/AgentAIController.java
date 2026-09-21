package xsl.sai.client.controller;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.message.Base64Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.agent.AgentAI;
import xsl.sai.client.pojo.dto.ChatDTO;
import xsl.sai.client.manager.ChatStreamRegistry;
import xsl.sai.client.service.AgentAIService;
import xsl.sai.framework.base.BaseController;
import xsl.sai.framework.holder.UserHolder;
import xsl.sai.framework.result.Result;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * &#064;DATE: 2026/6/15 15:48
 * &#064;AUTHOR: XSL
 *
 */
@RestController
@RequestMapping("/agent")
@Slf4j
public class AgentAIController extends BaseController {

    private final AgentAIService agentAIService;
    private final ChatStreamRegistry chatStreamRegistry;
    private final AgentAI agentAI;

    public AgentAIController(AgentAIService agentAIService, ChatStreamRegistry chatStreamRegistry, AgentAI agentAI) {
        this.agentAIService = agentAIService;
        this.chatStreamRegistry = chatStreamRegistry;
        this.agentAI = agentAI;
    }

    /**
     * 统一对话入口：根据 {@code ChatDTO} 中的模式状态分发到不同的 service 方法。
     * <ul>
     *   <li>{@code saaMode=true}  → SAA 编排模式（Spring AI Alibaba StateGraph 静态流水线）</li>
     *   <li>{@code loopMode=true} → 思考追踪模式（AgentScope 自驱动 ReAct 循环，SSE 流式返回 LoopStep）</li>
     *   <li>默认                  → 普通对话（支持文件上传）</li>
     * </ul>
     * 三种模式统一以 SSE（text/event-stream）返回，且共用 multipart（文件上传）信封。
     *
     * @param chatDTO  对话参数(JSON，置于 multipart 的 data 部分)
     * @param filePart 上传文件(可选)
     */
    @PostMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Flux<Result> chat(@RequestPart("data") ChatDTO chatDTO,
                             @RequestPart(value = "file", required = false) FilePart filePart) {
        if (Objects.isNull(chatDTO) || StrUtil.isAllBlank(chatDTO.getMessage())) {
            return fluxNone();
        }
        // 统一入口：由策略工厂按 ChatDTO 状态（saaMode / loopMode）分派到对应对话模式策略；
        // 文件源由 Controller 解析后透传（仅普通对话模式使用，SAA / 思考追踪忽略文件）。
        if (filePart != null) {
            return readFileAsSource(filePart)
                    .flatMapMany(source -> agentAIService.chat(chatDTO, source, filePart.filename()));
        }
        return agentAIService.chat(chatDTO);
    }

    /**
     * 中途打断（停止）指定会话的智能体思考 / 输出。
     * 由前端「停止」按钮调用；同时 SSE 连接断开时 WebFlux 自动 cancel 也会触发清理。
     *
     * <p>停止分两层，缺一不可：</p>
     * <ol>
     *   <li>{@code chatStreamRegistry.cancel} —— 取消该会话的 SSE 订阅输出，让前端立刻停止收字；</li>
     *   <li>{@code agentAI.stopSession} —— 真正中断后台的 AgentScope 主循环（思考 / 工具执行），
     *       避免「点了停止但后台 loop 仍在跑、下次对话续上残句」的问题。</li>
     * </ol>
     */
    @PostMapping("/chat/{conversationId}/stop")
    public Mono<Result> stop(@PathVariable("conversationId") String conversationId) {
        boolean cancelled = chatStreamRegistry.cancel(conversationId);
        return Mono.deferContextual(view -> {
            String userId = resolveUserId(view);
            boolean stopped = agentAI.stopSession(userId, conversationId);
            return Mono.just(Result.success(java.util.Map.of(
                    "cancelled", cancelled,
                    "stopped", stopped)));
        });
    }

    /**
     * 进入计划模式（UI 开关打开）。
     * 采用 AgentScope Java「管理台按钮」式程序化控制：前端开关直接调用，
     * 避免模型自行 plan_exit 绕过人工审批。
     */
    @PostMapping("/plan/enter")
    public Mono<Result> enterPlan(@RequestParam("conversationId") String conversationId) {
        return Mono.deferContextual(view -> {
            String userId = resolveUserId(view);
            try {
                agentAI.enterPlanMode(userId, conversationId);
            } catch (Exception e) {
                log.warn("[PlanMode] 进入失败 conv --> {}: {}", conversationId, e.getMessage());
                return Mono.just(Result.error("进入计划模式失败：" + e.getMessage()));
            }
            return Mono.just(Result.success(Map.of("active", true)));
        });
    }

    /**
     * 退出计划模式（UI「批准执行」或「取消」）。离开只读阶段，恢复默认权限。
     */
    @PostMapping("/plan/exit")
    public Mono<Result> exitPlan(@RequestParam("conversationId") String conversationId,
                                 @RequestParam(value = "approve", defaultValue = "true") boolean approve) {
        return Mono.deferContextual(view -> {
            String userId = resolveUserId(view);
            try {
                agentAI.exitPlanMode(userId, conversationId, approve);
            } catch (Exception e) {
                log.warn("[PlanMode] 退出失败 conv --> {}: {}", conversationId, e.getMessage());
                return Mono.just(Result.error("退出计划模式失败：" + e.getMessage()));
            }
            return Mono.just(Result.success(Map.of("active", false)));
        });
    }

    /**
     * 查询计划模式状态 + 当前计划内容。每次助手回复结束后由前端轮询，
     * 用于在面板中展示 PLAN.md 并决定是否展示「批准执行」按钮。
     */
    @GetMapping("/plan/status")
    public Mono<Result> planStatus(@RequestParam("conversationId") String conversationId) {
        return Mono.deferContextual(view -> {
            String userId = resolveUserId(view);
            AgentAI.PlanStatusView st = agentAI.getPlanStatus(userId, conversationId);
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("active", st.active());
            data.put("phase", st.phase());
            data.put("plan", st.plan());
            data.put("planFile", st.planFile());
            return Mono.just(Result.success(data));
        });
    }

    /**
     * 兜底写入计划文件：当模型未调用 plan_write（仅以文本回复呈现计划）时，前端把本轮回复 POST 回来，
     * 由后端写入 workspace/planDir/PLAN.md，使计划面板与「批准执行」流程可用。
     */
    @PostMapping("/plan/write")
    public Mono<Result> writePlan(@RequestParam("conversationId") String conversationId,
                                  @RequestBody Map<String, String> body) {
        return Mono.deferContextual(view -> {
            String userId = resolveUserId(view);
            String text = (body == null) ? null : body.get("text");
            try {
                agentAI.writePlan(userId, conversationId, text);
            } catch (Exception e) {
                log.warn("[PlanMode] 兜底写入失败 conv --> {}: {}", conversationId, e.getMessage());
                return Mono.just(Result.error("写入计划失败：" + e.getMessage()));
            }
            return Mono.just(Result.success(Map.of("ok", true)));
        });
    }

    /** 从 Reactor 上下文解析当前用户ID（与 AgentAIService 保持一致） */
    private String resolveUserId(reactor.util.context.ContextView view) {
        String uid = UserHolder.getUserId(view);
        return StrUtil.isNotBlank(uid) ? uid : "default";
    }

    /**
     * 读取文件内容并转为 Base64Source
     */
    private Mono<Base64Source> readFileAsSource(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    String mediaType = resolveMediaType(filePart.filename());
                    return Base64Source.builder().mediaType(mediaType).data(base64).build();
                });
    }

    private static final Map<String, String> EXT_TO_MIME = Map.ofEntries(
            Map.entry(".pdf",  "application/pdf"),
            Map.entry(".txt",  "text/plain"),
            Map.entry(".md",   "text/markdown"),
            Map.entry(".json", "application/json"),
            Map.entry(".xml",  "application/xml"),
            Map.entry(".html", "text/html"),
            Map.entry(".htm",  "text/html"),
            Map.entry(".csv",  "text/csv"),
            Map.entry(".png",  "image/png"),
            Map.entry(".jpg",  "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".gif",  "image/gif"),
            Map.entry(".svg",  "image/svg+xml"),
            Map.entry(".mp3",  "audio/mpeg"),
            Map.entry(".wav",  "audio/wav"),
            Map.entry(".mp4",  "video/mp4"),
            Map.entry(".doc",  "application/msword"),
            Map.entry(".docx", "application/msword"),
            Map.entry(".xls",  "application/vnd.ms-excel"),
            Map.entry(".xlsx", "application/vnd.ms-excel"),
            Map.entry(".ppt",  "application/vnd.ms-powerpoint"),
            Map.entry(".pptx", "application/vnd.ms-powerpoint")
    );

    /**
     * 根据文件名后缀推断 MIME 类型
     */
    private String resolveMediaType(String filename) {
        if (StrUtil.isBlank(filename)) return "application/octet-stream";
        String lower = filename.toLowerCase();
        // 从最后一个点处取后缀
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return "application/octet-stream";
        return EXT_TO_MIME.getOrDefault(lower.substring(dot), "application/octet-stream");
    }

}
