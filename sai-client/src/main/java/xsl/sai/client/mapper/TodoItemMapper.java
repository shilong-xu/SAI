package xsl.sai.client.mapper;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.domain.TodoItemEntity;

import java.time.LocalDateTime;

/**
 * 待办事项仓储
 *
 * @author SAI
 */
@Repository
public interface TodoItemMapper extends R2dbcRepository<TodoItemEntity, Long> {

    /** 覆盖 findById：SELECT * 即可（todo_item 不含 VECTOR 列，无需排除） */
    @Query("SELECT * FROM todo_item WHERE id = :id AND is_delete = 0")
    Mono<TodoItemEntity> findActiveById(@Param("id") Long id);

    /**
     * 分页查询（标题模糊 + 完成状态 + 类型；三个条件为空即不过滤，按更新时间倒序）
     *
     * @param kw   已含通配符的 LIKE 模式（为空表示不按标题过滤）
     * @param done 完成状态 0-未完成 1-已完成（为空表示不限）
     * @param type 类型 code 1-4（为空表示不限）
     */
    @Query("SELECT * FROM todo_item "
            + "WHERE is_delete = 0 "
            + "AND (:done IS NULL OR done = :done) "
            + "AND (:type IS NULL OR type = :type) "
            + "AND (:kw IS NULL OR title LIKE :kw) "
            + "ORDER BY update_time DESC, id DESC LIMIT :size OFFSET :offset")
    Flux<TodoItemEntity> pageFiltered(@Param("kw") String kw,
                                      @Param("done") Integer done,
                                      @Param("type") Integer type,
                                      @Param("size") int size,
                                      @Param("offset") int offset);

    /** 分页计数（筛选条件与 {@link #pageFiltered} 保持一致） */
    @Query("SELECT COUNT(*) FROM todo_item "
            + "WHERE is_delete = 0 "
            + "AND (:done IS NULL OR done = :done) "
            + "AND (:type IS NULL OR type = :type) "
            + "AND (:kw IS NULL OR title LIKE :kw)")
    Mono<Long> countFiltered(@Param("kw") String kw,
                             @Param("done") Integer done,
                             @Param("type") Integer type);

    /** 全局未完成计数（不受筛选影响，供页面头部展示） */
    @Query("SELECT COUNT(*) FROM todo_item WHERE is_delete = 0 AND done = 0")
    Mono<Long> countActive();

    /** 全部未删除条目数（仪表盘用） */
    @Query("SELECT COUNT(*) FROM todo_item WHERE is_delete = 0")
    Mono<Long> countAll();

    /** 指定时间区间内新增的条目数（仪表盘「今日新增」用，左闭右开） */
    @Query("SELECT COUNT(*) FROM todo_item "
            + "WHERE is_delete = 0 AND create_time >= :start AND create_time < :end")
    Mono<Long> countBetween(@Param("start") LocalDateTime start,
                            @Param("end") LocalDateTime end);
}
