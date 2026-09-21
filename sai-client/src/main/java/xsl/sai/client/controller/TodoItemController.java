package xsl.sai.client.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.dto.TodoItemDTO;
import xsl.sai.client.service.TodoItemService;
import xsl.sai.framework.enums.TodoType;
import xsl.sai.framework.result.Result;

/**
 * 待办事项接口（协作空间）
 *
 * <p>路由前缀 {@code /api/todo}，返回结构沿用 {@code Result.success(Object)}，分页用 {@code Map{list,total}}。
 *
 * @author SAI
 */
@Slf4j
@RestController
@RequestMapping("/api/todo")
public class TodoItemController {

    private final TodoItemService todoItemService;

    @Autowired
    public TodoItemController(TodoItemService todoItemService) {
        this.todoItemService = todoItemService;
    }

    /** 新增 / 修改 */
    @PostMapping
    public Mono<Result> save(@RequestBody(required = false) TodoItemDTO dto) {
        if (dto == null) {
            return Mono.just(Result.error(400, "请求体不能为空"));
        }
        return todoItemService.save(dto)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(500, "保存失败：" + e.getMessage())));
    }

    /**
     * 分页（标题模糊 + 完成状态 + 类型筛选；按更新时间倒序）
     *
     * <p><b>为什么 {@code done} / {@code type} 用 {@link String} 接收</b>：前端下拉在未选中 / 状态未初始化时
     * 可能把 {@code null}、{@code "undefined"} 之类的值拼进查询串。若这里声明为 {@code Integer}，
     * 会在<b>参数绑定阶段</b>直接抛 400（Bad Request），页面只会看到「请求失败 (400)」，
     * 连 Service 都进不去。故改为手工解析，非法值一律视为「不限」，从根上消除该类 400。
     *
     * @param keyword 标题模糊关键词（不传表示不限）
     * @param done    完成状态 0-未完成 1-已完成（不传 / 非法值均表示不限）
     * @param type    类型 code 1-4（不传 / 非法值均表示不限）
     */
    @GetMapping("/page")
    public Mono<Result> page(@RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String done,
                             @RequestParam(required = false) String type,
                             @RequestParam(defaultValue = "1") int pageNum,
                             @RequestParam(defaultValue = "20") int pageSize) {
        return todoItemService.page(keyword, parseDone(done), parseType(type), pageNum, pageSize)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(500, e.getMessage())));
    }

    /**
     * 容错解析完成状态：仅 {@code "0"} / {@code "1"} 视为有效筛选，
     * 其余（null、空串、"null"、"undefined"、任意非数字）一律视为「不限」。
     */
    private static Integer parseDone(String done) {
        if (done == null) {
            return null;
        }
        String v = done.trim();
        return ("0".equals(v) || "1".equals(v)) ? Integer.valueOf(v) : null;
    }

    /**
     * 容错解析类型：仅合法 code（1-4）视为有效筛选，其余一律视为「不限」。
     * 同时兼容中文标签（如 {@code type=会议}），方便手写 URL / curl 调试。
     */
    private static Integer parseType(String type) {
        return TodoType.parse(type);
    }

    /** 详情 */
    @GetMapping("/{id}")
    public Mono<Result> detail(@PathVariable Long id) {
        return todoItemService.detail(id)
                .map(Result::success)
                .switchIfEmpty(Mono.just(Result.error(404, "待办不存在或已删除")))
                .onErrorResume(e -> Mono.just(Result.error(500, e.getMessage())));
    }

    /** 删除（逻辑删除） */
    @DeleteMapping("/{id}")
    public Mono<Result> remove(@PathVariable Long id) {
        return todoItemService.remove(id)
                .then(Mono.just(Result.success("ok")))
                .onErrorResume(e -> Mono.just(Result.error(500, "删除失败：" + e.getMessage())));
    }

    /** 快速切换完成状态（列表勾选 / 取消） */
    @PostMapping("/{id}/toggle")
    public Mono<Result> toggle(@PathVariable Long id) {
        return todoItemService.toggleDone(id)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(500, "操作失败：" + e.getMessage())));
    }
}
