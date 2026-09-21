package xsl.sai.client.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.dto.KnowledgeBaseDTO;
import xsl.sai.client.pojo.vo.KnowledgeBaseVO;

import java.util.List;
import java.util.Map;

/**
 * 知识库 RAG 服务
 *
 * @author SAI
 */
public interface KnowledgeBaseService {

    /** 新增或更新（保存时语义切割 + 向量化，切块落 knowledge_chunk） */
    Mono<KnowledgeBaseVO> save(KnowledgeBaseDTO dto);

    /**
     * 分页查询（按更新时间倒序），返回 Map{list, total} 与前端约定一致。
     *
     * @param keyword 关键词模糊搜索：命中<b>正文</b>或该条目已提炼的<b>关键词</b>（knowledge_chunk.keyword）；
     *                为 null / 空白时不过滤，返回全量分页
     */
    Mono<Map<String, Object>> page(int pageNum, int pageSize, String keyword);

    /** 详情（含完整文本内容，供预览） */
    Mono<KnowledgeBaseVO> detail(Long id);

    /** 逻辑删除（级联逻辑删除切块） */
    Mono<Void> remove(Long id);

    /** 语义检索（向量召回 knowledge_chunk，按相似度降序，threshold 为可选相似度阈值） */
    Flux<KnowledgeBaseVO> semanticSearch(String text, int topK, Double threshold);

    /** 只提炼关键词、不落库（前端编辑弹窗「自动生成关键词」预览用） */
    Mono<List<String>> extractKeywordsOnly(String content);
}
