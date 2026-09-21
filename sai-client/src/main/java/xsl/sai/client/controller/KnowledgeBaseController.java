package xsl.sai.client.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.dto.KnowledgeBaseDTO;
import xsl.sai.client.service.KnowledgeBaseService;
import xsl.sai.framework.result.Result;

/**
 * 知识库 RAG 接口（给人维护 + 给 Agent 检索）
 *
 * <p>路由前缀 {@code /api/knowledge}，与现有 {@code /api/word} 风格一致；
 * 返回结构沿用 {@code Result.success(Object)} 裸类型，分页用 {@code Map{list,total}}。
 *
 * @author SAI
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @Autowired
    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** 新增 / 修改（保存时语义切割 + 向量化） */
    @PostMapping
    public Mono<Result> save(@RequestBody(required = false) KnowledgeBaseDTO dto) {
        if (dto == null) {
            return Mono.just(Result.error(400, "请求体不能为空"));
        }
        return knowledgeBaseService.save(dto)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(500, "保存失败：" + e.getMessage())));
    }

    /**
     * 只提炼关键词、不落库 —— 编辑弹窗里点「自动生成」时用，让用户在保存前就能看到并微调关键词。
     *
     * <p>与 {@code POST /api/knowledge} 分开：保存接口会把关键词连同向量一起写入，
     * 而这个接口是纯预览，方便用户改完再保存。
     */
    @PostMapping("/extract-keywords")
    public Mono<Result> extractKeywords(@RequestBody(required = false) KnowledgeBaseDTO dto) {
        if (dto == null || dto.getContent() == null || dto.getContent().isBlank()) {
            return Mono.just(Result.error(400, "请先填写正文内容"));
        }
        return knowledgeBaseService.extractKeywordsOnly(dto.getContent())
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(500, "关键词提炼失败：" + e.getMessage())));
    }

    /**
     * 分页（按更新时间倒序）。
     *
     * <p>关键词模糊搜索由 {@code keyword} 承担：命中<b>正文</b>或该条目已提炼的<b>关键词</b>即算命中；
     * 不传（或空白）时返回全量分页 —— 与「语义检索」是两条独立通道，前者精确可预期、支持分页，后者按相关度召回。
     */
    @GetMapping("/page")
    public Mono<Result> page(@RequestParam(defaultValue = "1") int pageNum,
                             @RequestParam(defaultValue = "10") int pageSize,
                             @RequestParam(required = false) String keyword) {
        return knowledgeBaseService.page(pageNum, pageSize, keyword)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(500, e.getMessage())));
    }

    /** 详情（预览完整文本） */
    @GetMapping("/{id}")
    public Mono<Result> detail(@PathVariable Long id) {
        return knowledgeBaseService.detail(id)
                .map(Result::success)
                .switchIfEmpty(Mono.just(Result.error(404, "知识库条目不存在或已删除")))
                .onErrorResume(e -> Mono.just(Result.error(500, e.getMessage())));
    }

    /** 删除（逻辑删除，级联切块） */
    @DeleteMapping("/{id}")
    public Mono<Result> remove(@PathVariable Long id) {
        return knowledgeBaseService.remove(id)
                .then(Mono.just(Result.success("ok")))
                .onErrorResume(e -> Mono.just(Result.error(500, "删除失败：" + e.getMessage())));
    }

    /**
     * 语义检索（向量召回 knowledge_chunk 的关键词，再回填对应 knowledge_base 正文）
     *
     * <p>threshold 给默认值而非必传：知识库以【关键词】建索引，查询长句与短关键词的余弦相似度天然偏低
     * （最相关命中通常 0.5~0.75），默认值取 0.6；缺失参数时若直接 400，Service 里的兜底常量形同虚设。
     */
    @GetMapping("/semantic")
    public Mono<Result> semantic(@RequestParam String text,
                                 @RequestParam(defaultValue = "5") int topK,
                                 @RequestParam(defaultValue = "0.6") Double threshold) {
        return knowledgeBaseService.semanticSearch(text, topK, threshold).collectList()
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(500, "语义检索失败：" + e.getMessage())));
    }
}
