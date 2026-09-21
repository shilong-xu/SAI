package xsl.sai.framework.base;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;

import java.time.LocalDateTime;

/**
 * 基础实体，抽取公共字段
 *
 * @author XSL
 * @date 2026-06-23
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseEntity {

    /** 备注 */
    @Column("remark")
    private String remark;

    /** 创建时间 */
    @CreatedDate
    @Column("create_time")
    private LocalDateTime createTime;

    /** 更新时间 */
    @LastModifiedDate
    @Column("update_time")
    private LocalDateTime updateTime;

    /** 是否删除（新记录默认未删，避免 INSERT 时 is_delete 为 NULL 触发 NOT NULL 约束） */
    @Column("is_delete")
    private Boolean isDelete = false;

    /**
     * 标记是否为新建实体（非持久化字段）
     * 配合 Persistable.isNew() 使用，用于 ReactiveCrudRepository.save() 区分 INSERT / UPDATE
     *
     * <p>同时加 {@code @JsonIgnore}：如果 Controller 不小心直接返回 Entity，
     * 也不会把 {@code "new": false} 暴露给前端（Jackson 默认会识别 {@code isXxx()} 为属性）
     */
    @Transient
    @JsonIgnore
    protected boolean isNewEntity = false;
}
