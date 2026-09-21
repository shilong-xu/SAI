package xsl.sai.client.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import xsl.sai.client.domain.TodoItemEntity;
import xsl.sai.client.mapper.TodoItemMapper;
import xsl.sai.client.pojo.dto.TodoItemDTO;
import xsl.sai.client.pojo.vo.TodoItemVO;
import xsl.sai.client.service.TodoItemService;
import xsl.sai.framework.enums.TodoType;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 待办事项服务实现
 *
 * @author SAI
 */
@Slf4j
@Service
public class TodoItemServiceImpl implements TodoItemService {

    private final TodoItemMapper todoItemMapper;

    public TodoItemServiceImpl(TodoItemMapper todoItemMapper) {
        this.todoItemMapper = todoItemMapper;
    }

    @Override
    public Mono<TodoItemVO> save(TodoItemDTO dto) {
        if (dto == null || dto.getTitle() == null || dto.getTitle().isBlank()) {
            return Mono.error(new IllegalArgumentException("标题不能为空"));
        }
        final String title = dto.getTitle().trim();
        // 类型：数字 code（1 任务 / 2 事项 / 3 会议 / 4 回信）。
        // 非法 / 越界 / 缺失统一归一为 null（未指定），不因脏值让保存失败。
        final Integer type = TodoType.normalize(dto.getType());
        // 内容（Markdown）可选：空串统一落库为 null，避免前端判断空内容时出现「有值但为空」的分支
        final String content = (dto.getContent() == null || dto.getContent().isBlank())
                ? null : dto.getContent().trim();
        final Integer done = (dto.getDone() != null && dto.getDone() == 1) ? 1 : 0;
        if (dto.getId() != null) {
            // 编辑：保留原记录，更新标题、类型、内容与完成状态
            return todoItemMapper.findActiveById(dto.getId())
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("待办不存在或已删除: " + dto.getId())))
                    .flatMap(e -> {
                        e.setTitle(title);
                        e.setType(type);
                        e.setContent(content);
                        e.setDone(done);
                        e.setUpdateTime(LocalDateTime.now());
                        return todoItemMapper.save(e);
                    })
                    .map(this::toVO);
        }
        TodoItemEntity e = new TodoItemEntity();
        e.setTitle(title);
        e.setType(type);
        e.setContent(content);
        e.setDone(done);
        return todoItemMapper.save(e).map(this::toVO);
    }

    @Override
    public Mono<Map<String, Object>> page(String keyword, Integer done, Integer type, int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.max(pageSize, 1);
        int offset = (page - 1) * size;
        // 三个筛选条件统一归一：空值 / 非法值 → null（SQL 侧走 :x IS NULL 分支，即不过滤）
        final String kw = (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim() + "%";
        final Integer typeFilter = TodoType.normalize(type);
        final Integer doneFilter = (done != null && (done == 0 || done == 1)) ? done : null;
        return todoItemMapper.countFiltered(kw, doneFilter, typeFilter).defaultIfEmpty(0L)
                .flatMap(total -> Mono.zip(
                                todoItemMapper.pageFiltered(kw, doneFilter, typeFilter, size, offset)
                                        .map(this::toVO)
                                        .collectList(),
                                todoItemMapper.countActive().defaultIfEmpty(0L))
                        .map(t -> {
                            Map<String, Object> m = new LinkedHashMap<>(3);
                            m.put("list", t.getT1());
                            m.put("total", total);
                            m.put("active", t.getT2());
                            return m;
                        }));
    }

    @Override
    public Mono<TodoItemVO> detail(Long id) {
        return todoItemMapper.findActiveById(id).map(this::toVO);
    }

    @Override
    @Transactional
    public Mono<Void> remove(Long id) {
        return todoItemMapper.findActiveById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("待办不存在或已删除: " + id)))
                .flatMap(e -> {
                    e.setIsDelete(true);
                    e.setUpdateTime(LocalDateTime.now());
                    return todoItemMapper.save(e);
                })
                .then();
    }

    @Override
    @Transactional
    public Mono<TodoItemVO> toggleDone(Long id) {
        return todoItemMapper.findActiveById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("待办不存在或已删除: " + id)))
                .flatMap(e -> {
                    e.setDone((e.getDone() != null && e.getDone() == 1) ? 0 : 1);
                    e.setUpdateTime(LocalDateTime.now());
                    return todoItemMapper.save(e);
                })
                .map(this::toVO);
    }

    private TodoItemVO toVO(TodoItemEntity e) {
        TodoItemVO v = new TodoItemVO();
        BeanUtils.copyProperties(e, v);
        return v;
    }
}
