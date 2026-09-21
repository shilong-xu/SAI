package xsl.sai.client.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.quartz.SchedulerException;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.dto.ScheduleTaskDTO;
import xsl.sai.client.pojo.vo.ScheduleLogVO;
import xsl.sai.client.pojo.vo.ScheduleTaskVO;
import xsl.sai.client.service.ScheduleService;
import xsl.sai.schedule.core.DynamicScheduleManager;
import xsl.sai.schedule.domain.ScheduleLog;
import xsl.sai.schedule.domain.ScheduleTask;
import xsl.sai.schedule.mapper.ScheduleLogMapper;
import xsl.sai.schedule.mapper.ScheduleTaskMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定时任务 服务实现（Quartz 版）
 *
 * @author SAI
 */
@Slf4j
@Service
public class ScheduleServiceImpl implements ScheduleService {

    /** 通用任务分组名（与 AgentScheduleController 的 AGENT 分组对称，实现数据隔离） */
    private static final String GROUP_COMMON = "DEFAULT";

    private final ScheduleTaskMapper taskMapper;
    private final ScheduleLogMapper logMapper;
    private final DynamicScheduleManager scheduleManager;

    public ScheduleServiceImpl(ScheduleTaskMapper taskMapper,
                               ScheduleLogMapper logMapper,
                               DynamicScheduleManager scheduleManager) {
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.scheduleManager = scheduleManager;
    }

    @Override
    public Flux<ScheduleTaskVO> page(long page, long size) {
        long offset = Math.max(0, page - 1) * size;
        // 通用任务仅查 DEFAULT 分组，与 Agent 任务（AGENT 分组）隔离
        return taskMapper.pageByGroup(GROUP_COMMON, null, offset, size).map(this::toVO);
    }

    @Override
    public Mono<Long> count() {
        return taskMapper.countByGroup(GROUP_COMMON, null);
    }

    @Override
    public Mono<Long> create(ScheduleTaskDTO dto) {
        ScheduleTask task = toEntity(dto);
        return taskMapper.insert(task)
                .doOnSuccess(id -> {
                    // 回填数据库自增主键：否则下方注册到 Quartz 的 JobDataMap 中 taskId 为 null，
                    // 任务执行时写 schedule_log 会因 Column 'task_id' cannot be null 失败。
                    task.setId(id);
                    if (task.getStatus() != null && task.getStatus() == 1) {
                        scheduleManager.refresh(task);
                    }
                });
    }

    @Override
    public Mono<Long> update(ScheduleTaskDTO dto) {
        return taskMapper.findById(dto.getId())
                .flatMap(existing -> {
                    existing.setName(dto.getName());
                    existing.setCron(dto.getCron());
                    existing.setInvokeTarget(dto.getInvokeTarget());
                    if (dto.getConcurrent() != null) existing.setConcurrent(dto.getConcurrent());
                    if (dto.getTimeout() != null) existing.setTimeout(dto.getTimeout());
                    if (dto.getStatus() != null) existing.setStatus(dto.getStatus());
                    if (dto.getRemark() != null) existing.setRemark(dto.getRemark());
                    return taskMapper.update(existing).map(updated -> {
                        if (existing.getStatus() != null && existing.getStatus() == 1) {
                            scheduleManager.refresh(existing);
                        } else {
                            scheduleManager.stop(existing.getName());
                        }
                        return updated;
                    });
                });
    }

    @Override
    public Mono<Void> toggle(long id) {
        return taskMapper.findById(id)
                .flatMap(task -> {
                    int next = (task.getStatus() != null && task.getStatus() == 1) ? 0 : 1;
                    task.setStatus(next);
                    return taskMapper.update(task).then(Mono.fromRunnable(() -> {
                        if (next == 1) {
                            scheduleManager.refresh(task);
                        } else {
                            scheduleManager.stop(task.getName());
                        }
                    }));
                });
    }

    @Override
    public Mono<Void> runNow(long id) {
        return taskMapper.findById(id)
                .flatMap(task -> Mono.fromRunnable(() -> scheduleManager.runOnce(task)))
                .then();
    }

    @Override
    public Mono<Long> remove(long id) {
        // 先停掉调度器中的任务（无论是否启用都要停，避免 Quartz 残留触发）
        return taskMapper.findById(id)
                .flatMap(task -> {
                    scheduleManager.stop(task.getName());
                    return taskMapper.logicDeleteById(id);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // id 不存在（可能已删）：仍尝试停一次，再返回 0
                    scheduleManager.stop(String.valueOf(id));
                    return Mono.just(0L);
                }));
    }

    @Override
    public Flux<ScheduleLogVO> logs(long taskId, long limit) {
        return logMapper.findByTask(taskId, limit).map(this::toLogVO);
    }

    @Override
    public Flux<ScheduleLogVO> recentLogs(long limit) {
        return logMapper.listRecent(limit, null).map(this::toLogVO);
    }

    @Override
    public Flux<ScheduleLogVO> recentLogs(long limit, String type) {
        return logMapper.listRecent(limit, type).map(this::toLogVO);
    }

    @Override
    public Map<String, String> allowedBeans() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("sampleScheduleTask", "示例任务（打印调度触发）");
        map.put("emailReceiveService", "邮件抓取任务（每5分钟拉取收件箱并入库）");
        return map;
    }

    // ======================== 实体 ↔ DTO/VO ========================

    private ScheduleTaskVO toVO(ScheduleTask t) {
        ScheduleTaskVO vo = new ScheduleTaskVO();
        BeanUtils.copyProperties(t, vo);
        return vo;
    }

    private ScheduleLogVO toLogVO(ScheduleLog l) {
        ScheduleLogVO vo = new ScheduleLogVO();
        vo.setId(l.getId());
        vo.setTaskId(l.getTaskId());
        vo.setTaskName(l.getTaskName());
        vo.setInvokeTarget(l.getInvokeTarget());
        vo.setTaskType(l.getTaskType());
        vo.setSuccess(l.getSuccess());
        vo.setErrorMsg(l.getErrorMsg());
        vo.setResult(l.getResult());
        vo.setCostMs(l.getCostMs());
        vo.setCreateTime(l.getCreateTime());
        return vo;
    }

    private ScheduleTask toEntity(ScheduleTaskDTO dto) {
        ScheduleTask t = new ScheduleTask();
        t.setName(dto.getName());
        t.setCron(dto.getCron());
        t.setGroupName(dto.getGroupName() != null ? dto.getGroupName() : "DEFAULT");
        t.setDescription(dto.getDescription() != null ? dto.getDescription() : "");
        t.setInvokeTarget(dto.getInvokeTarget());
        t.setConcurrent(dto.getConcurrent() == null ? 0 : dto.getConcurrent());
        t.setTimeout(dto.getTimeout() == null ? 600 : dto.getTimeout());
        t.setStatus(dto.getStatus() == null ? 0 : dto.getStatus());
        t.setRemark(dto.getRemark());
        return t;
    }
}
