package xsl.sai.client.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.dto.ScheduleTaskDTO;
import xsl.sai.client.service.ScheduleService;
import xsl.sai.framework.base.BaseController;
import xsl.sai.framework.result.Result;
import xsl.sai.schedule.mapper.ScheduleTaskMapper;

import java.util.Map;

/**
 * 定时任务 HTTP 接口
 *
 * <p>与前端 sai-page 的 app.js 调用契约一致：
 * <ul>
 *   <li>GET  /api/schedule/page      分页列表（query: page, size）</li>
 *   <li>POST /api/schedule           新增</li>
 *   <li>PUT  /api/schedule           更新</li>
 *   <li>POST /api/schedule/{id}/toggle  启用/停用（body: status）</li>
 *   <li>POST /api/schedule/{id}/run      立即执行一次</li>
 *   <li>DELETE /api/schedule/{id}        删除（逻辑）</li>
 *   <li>GET  /api/schedule/{id}/logs     执行日志（query: limit）</li>
 *   <li>GET  /api/schedule/logs          全局运行日志（query: limit）</li>
 *   <li>GET  /api/schedule/beans         白名单 bean 列表</li>
 * </ul>
 *
 * @author SAI
 */
@Slf4j
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController extends BaseController {

    private final ScheduleService scheduleService;
    private final ScheduleTaskMapper taskMapper;

    public ScheduleController(ScheduleService scheduleService,
                              ScheduleTaskMapper taskMapper) {
        this.scheduleService = scheduleService;
        this.taskMapper = taskMapper;
    }

    /** 分页列表（前端契约：?keyword=&page=&size=，返回 {data:{list,total}}） */
    @GetMapping("/page")
    public Mono<Result> page(@RequestParam(defaultValue = "") String keyword,
                             @RequestParam(defaultValue = "1") long page,
                             @RequestParam(defaultValue = "10") long size) {
        return scheduleService.page(page, size)
                .collectList()
                .zipWith(scheduleService.count())
                .map(tuple -> {
                    Map<String, Object> data = new java.util.LinkedHashMap<>();
                    data.put("list", tuple.getT1());
                    data.put("total", tuple.getT2());
                    return Result.success(data);
                })
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 新增 */
    @PostMapping
    public Mono<Result> create(@RequestBody ScheduleTaskDTO dto) {
        if (dto == null || dto.getName() == null || dto.getCron() == null || dto.getInvokeTarget() == null) {
            return Mono.just(Result.error("任务名、Cron、调用目标均必填"));
        }
        return scheduleService.create(dto)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 更新 */
    @PutMapping
    public Mono<Result> update(@RequestBody ScheduleTaskDTO dto) {
        if (dto == null || dto.getId() == null) {
            return Mono.just(Result.error("更新需提供任务 id"));
        }
        return scheduleService.update(dto)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 启用/停用（前端不传 body，由当前状态反推） */
    @PostMapping("/{id}/toggle")
    public Mono<Result> toggle(@PathVariable long id) {
        return scheduleService.toggle(id)
                .then(Mono.just(Result.success("ok")))
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 立即执行一次 */
    @PostMapping("/{id}/run")
    public Mono<Result> run(@PathVariable long id) {
        return scheduleService.runNow(id)
                .then(Mono.just(Result.success("ok")))
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 删除（逻辑） */
    @DeleteMapping("/{id}")
    public Mono<Result> remove(@PathVariable long id) {
        return scheduleService.remove(id)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 任务详情（前端契约：返回 {data:{...}}） */
    @GetMapping("/{id}")
    public Mono<Result> detail(@PathVariable long id) {
        return taskMapper.findById(id)
                .map(Result::success)
                .switchIfEmpty(Mono.just(Result.error("任务不存在")))
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /** 执行日志（前端契约：?size=，返回 {data:[...]}） */
    @GetMapping("/{id}/logs")
    public Flux<Result> logs(@PathVariable long id,
                             @RequestParam(defaultValue = "20") long size) {
        return scheduleService.logs(id, size)
                .map(Result::success);
    }

    /** 白名单 bean 列表 */
    @GetMapping("/beans")
    public Mono<Result> beans() {
        Map<String, String> map = scheduleService.allowedBeans();
        return Mono.just(Result.success(map));
    }

    /** 全局运行日志（前端契约：?limit=&type=，type=COMMON/AGENT 可区分任务类型，返回 {data:[...]}） */
    @GetMapping("/logs")
    public Mono<Result> allLogs(@RequestParam(defaultValue = "100") long limit,
                                @RequestParam(required = false) String type) {
        return scheduleService.recentLogs(limit, type)
                .collectList()
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }
}
