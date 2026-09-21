package xsl.sai.client.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.dto.ScheduleTaskDTO;
import xsl.sai.client.pojo.vo.ScheduleLogVO;
import xsl.sai.client.pojo.vo.ScheduleTaskVO;

import java.util.Map;

/**
 * 定时任务 业务接口
 *
 * @author SAI
 */
public interface ScheduleService {

    /** 分页查询任务列表 */
    Flux<ScheduleTaskVO> page(long page, long size);

    /** 总数 */
    Mono<Long> count();

    /** 创建任务 */
    Mono<Long> create(ScheduleTaskDTO dto);

    /** 更新任务 */
    Mono<Long> update(ScheduleTaskDTO dto);

    /** 启用/停用（切换当前状态） */
    Mono<Void> toggle(long id);

    /** 立即触发一次（通过 DynamicScheduleManager 注册临时执行） */
    Mono<Void> runNow(long id);

    /** 删除（逻辑删除） */
    Mono<Long> remove(long id);

    /** 查询某任务最近执行日志 */
    Flux<ScheduleLogVO> logs(long taskId, long limit);

    /** 查询全部任务最近执行日志（全局运行日志） */
    Flux<ScheduleLogVO> recentLogs(long limit);

    /** 按任务类型查询最近执行日志（type: COMMON / AGENT，null 表示全部） */
    Flux<ScheduleLogVO> recentLogs(long limit, String type);

    /** 白名单内的可调用 bean 列表（供前端下拉选择） */
    Map<String, String> allowedBeans();
}
