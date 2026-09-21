package xsl.sai.client.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.dto.AgentScheduleDTO;
import xsl.sai.client.pojo.dto.ScheduleTaskDTO;
import xsl.sai.client.service.ScheduleService;
import xsl.sai.framework.base.BaseController;
import xsl.sai.framework.result.Result;
import xsl.sai.schedule.mapper.ScheduleLogMapper;
import xsl.sai.schedule.mapper.ScheduleTaskMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 定时任务 HTTP 接口
 *
 * <p>专用于管理调用 {@code scheduleTask.agentChat(message)} 的定时任务。
 * 创建时自动将 message 转为 invoke_target，列表仅展示 AGENT 分组的任务。
 *
 * <p>前端契约：
 * <ul>
 *   <li>GET  /api/agent-schedule/page   分页列表</li>
 *   <li>POST /api/agent-schedule        新增</li>
 *   <li>PUT  /api/agent-schedule        更新</li>
 *   <li>POST /api/agent-schedule/{id}/toggle  启用/停用</li>
 *   <li>POST /api/agent-schedule/{id}/run      立即执行</li>
 *   <li>DELETE /api/agent-schedule/{id}        删除</li>
 *   <li>GET  /api/agent-schedule/{id}/logs     执行日志</li>
 *   <li>GET  /api/agent-schedule/logs          全局运行日志</li>
 * </ul>
 *
 * @author SAI
 */
@Slf4j
@RestController
@RequestMapping("/api/agent-schedule")
public class AgentScheduleController extends BaseController {

    private static final String AGENT_GROUP = "AGENT";

    private final ScheduleService scheduleService;
    private final ScheduleTaskMapper taskMapper;
    private final ScheduleLogMapper logMapper;

    public AgentScheduleController(ScheduleService scheduleService,
                                   ScheduleTaskMapper taskMapper,
                                   ScheduleLogMapper logMapper) {
        this.scheduleService = scheduleService;
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
    }

    /** 分页列表——仅 AGENT 分组 */
    @GetMapping("/page")
    public Mono<Result> page(@RequestParam(defaultValue = "1") long page,
                             @RequestParam(defaultValue = "10") long size,
                             @RequestParam(defaultValue = "") String keyword) {
        long offset = Math.max(0, page - 1) * size;
        String kw = keyword.isBlank() ? null : keyword.trim();
        return taskMapper.pageByGroup(AGENT_GROUP, kw, offset, size)
                .map(t -> {
                    // 从 invoke_target 中提取 message 便于前端展示
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("name", t.getName());
                    m.put("description", t.getDescription());
                    m.put("invokeTarget", t.getInvokeTarget());
                    m.put("message", extractMessage(t.getInvokeTarget()));
                    m.put("cron", t.getCron());
                    m.put("status", t.getStatus());
                    m.put("concurrent", t.getConcurrent());
                    m.put("groupName", t.getGroupName());
                    return m;
                })
                .collectList()
                .zipWith(taskMapper.countByGroup(AGENT_GROUP, kw))
                .map(tuple -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("list", tuple.getT1());
                    data.put("total", tuple.getT2());
                    return Result.success(data);
                })
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 新增 */
    @PostMapping
    public Mono<Result> create(@RequestBody AgentScheduleDTO dto) {
        if (dto == null || isBlank(dto.getName()) || isBlank(dto.getCron()) || isBlank(dto.getMessage())) {
            return Mono.just(Result.error("任务名、Cron、Agent消息均必填"));
        }
        ScheduleTaskDTO task = buildTaskDTO(dto);
        return scheduleService.create(task)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 更新 */
    @PutMapping
    public Mono<Result> update(@RequestBody AgentScheduleDTO dto) {
        if (dto == null || dto.getId() == null) {
            return Mono.just(Result.error("更新需提供任务 id"));
        }
        ScheduleTaskDTO task = buildTaskDTO(dto);
        return scheduleService.update(task)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 启用/停用 */
    @PostMapping("/{id}/toggle")
    public Mono<Result> toggle(@PathVariable long id) {
        return scheduleService.toggle(id)
                .then(Mono.just(Result.success("ok")))
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 立即执行 */
    @PostMapping("/{id}/run")
    public Mono<Result> run(@PathVariable long id) {
        return scheduleService.runNow(id)
                .then(Mono.just(Result.success("ok")))
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public Mono<Result> remove(@PathVariable long id) {
        return scheduleService.remove(id)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 任务详情（前端契约：返回 {data:{...}}，含提取出的 message） */
    @GetMapping("/{id}")
    public Mono<Result> detail(@PathVariable long id) {
        return taskMapper.findById(id)
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("name", t.getName());
                    m.put("description", t.getDescription());
                    m.put("invokeTarget", t.getInvokeTarget());
                    m.put("message", extractMessage(t.getInvokeTarget()));
                    m.put("cron", t.getCron());
                    m.put("status", t.getStatus());
                    m.put("concurrent", t.getConcurrent());
                    m.put("timeout", t.getTimeout());
                    m.put("groupName", t.getGroupName());
                    m.put("createTime", t.getCreateTime());
                    m.put("updateTime", t.getUpdateTime());
                    return m;
                })
                .map(Result::success)
                .switchIfEmpty(Mono.just(Result.error("任务不存在")))
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 执行日志 */
    @GetMapping("/{id}/logs")
    public Mono<Result> logs(@PathVariable long id,
                             @RequestParam(defaultValue = "20") long size) {
        return logMapper.findByTask(id, Math.max(1, size))
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", l.getId());
                    m.put("result", l.getResult());
                    m.put("error", l.getErrorMsg());
                    m.put("createTime", l.getCreateTime());
                    return m;
                })
                .collectList()
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 全局运行日志（仅 AGENT 分组） */
    @GetMapping("/logs")
    public Mono<Result> allLogs(@RequestParam(defaultValue = "100") long limit) {
        return scheduleService.recentLogs(limit, AGENT_GROUP)
                .collectList()
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    // ======================== 工具方法 ========================

    /** DTO → ScheduleTaskDTO，自动构建 invoke_target */
    private ScheduleTaskDTO buildTaskDTO(AgentScheduleDTO dto) {
        ScheduleTaskDTO task = new ScheduleTaskDTO();
        task.setId(dto.getId());
        task.setName(dto.getName());
        task.setCron(dto.getCron());
        task.setGroupName(isBlank(dto.getGroupName()) ? AGENT_GROUP : dto.getGroupName());
        task.setDescription(dto.getDescription() != null ? dto.getDescription() : "");
        task.setInvokeTarget("scheduleTask.agentChat('" + escape(dto.getMessage()) + "')");
        task.setConcurrent(1);
        task.setTimeout(600);
        task.setStatus(1);
        return task;
    }

    /** 从 invoke_target 中提取 message 供前端展示 */
    private String extractMessage(String invokeTarget) {
        if (invokeTarget == null) return "";
        // scheduleTask.agentChat('the message')
        int start = invokeTarget.indexOf("('");
        int end = invokeTarget.lastIndexOf("')");
        if (start >= 0 && end > start) {
            return unescape(invokeTarget.substring(start + 2, end));
        }
        return invokeTarget;
    }

    /** 转义消息中的单引号（防止破坏 invoke_target 语法） */
    private String escape(String msg) {
        return msg == null ? "" : msg.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** 反转义 */
    private String unescape(String msg) {
        return msg == null ? "" : msg.replace("\\'", "'").replace("\\\\", "\\");
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
