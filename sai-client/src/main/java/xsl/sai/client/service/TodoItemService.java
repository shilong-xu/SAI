package xsl.sai.client.service;

import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.dto.TodoItemDTO;
import xsl.sai.client.pojo.vo.TodoItemVO;

import java.util.Map;

/**
 * 待办事项服务
 *
 * @author SAI
 */
public interface TodoItemService {

    /** 新增 / 修改 */
    Mono<TodoItemVO> save(TodoItemDTO dto);

    /**
     * 分页（按更新时间倒序）
     *
     * @param keyword  标题模糊关键词（可空）
     * @param done     完成状态 0-未完成 1-已完成（为空表示不限）
     * @param type     类型 code 1-4（见 {@link xsl.sai.framework.enums.TodoType}；为空表示不限）
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return {list, total（当前筛选命中数）, active（全局未完成数）}
     */
    Mono<Map<String, Object>> page(String keyword, Integer done, Integer type, int pageNum, int pageSize);

    /** 详情 */
    Mono<TodoItemVO> detail(Long id);

    /** 逻辑删除 */
    Mono<Void> remove(Long id);

    /** 快速切换完成状态（列表勾选/取消） */
    Mono<TodoItemVO> toggleDone(Long id);
}
