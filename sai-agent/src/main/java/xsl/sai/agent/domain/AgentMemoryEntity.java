package xsl.sai.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;
import xsl.sai.framework.base.BaseEntity;

import java.time.LocalDateTime;

/**
 * Agent 长期记忆实体（agent_memory）
 *
 * <p>与「短期记忆」{@code AgentState}（存 Redis）相对，本表存<b>跨会话</b>的长期事实，
 * 每条记忆单独向量化（bge-m3，1024 维），供每轮对话按余弦相似度召回后注入上下文。
 *
 * <p>实现 {@link Persistable}：通过 {@code isNewEntity} 标记区分 INSERT / UPDATE。
 * 注意写入走 {@code R2dbcClient} 原生 SQL（向量列需 {@code STRING_TO_VECTOR} 转换），
 * 不走 {@code ReactiveCrudRepository.save()}。
 *
 * @author SAI
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("agent_memory")
public class AgentMemoryEntity extends BaseEntity implements Persistable<Long> {

    /** 主键ID（自增） */
    @Id
    private Long id;

    /** 归属用户（记忆隔离维度，空串表示匿名） */
    private String userId;

    /** 产生记忆的 Agent 名称 */
    private String agentName;

    /** 来源会话（可为空：长期记忆本身应跨会话） */
    private String sessionId;

    /** 记忆类型 FACT | EVENT | PREFERENCE | SUMMARY */
    private String memoryType;

    /** 记忆正文 */
    private String content;

    /** 来源 RAW | EXTRACT | TOOL | MANUAL */
    private String source;

    /** 重要度 1-5 */
    private Integer importance;

    /** 向量（bge-m3 1024维）；非表列映射，仅写入/召回时作为载体 */
    @Transient
    private transient float[] embedding;

    /** 被召回次数 */
    private Integer accessCount;

    /** 最近召回时间 */
    private LocalDateTime lastAccessTime;

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNewEntity;
    }
}
