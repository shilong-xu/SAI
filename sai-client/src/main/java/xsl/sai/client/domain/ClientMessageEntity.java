package xsl.sai.client.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;
import xsl.sai.framework.base.BaseEntity;

/**
 * 会话消息记录领域实体（client_ 模块）
 * <p>使用 Spring Data R2DBC 映射，简单 CRUD 优先走 {@code MessageMapper} 自带方法；
 * 复杂自定义 SQL（如按会话查询、统计消息数）才使用 {@code R2dbcClient}。
 *
 * <p>实现 {@link Persistable}：通过 {@code isNewEntity} 标记区分 INSERT / UPDATE。
 * 消息主键为自增，新增时不设置 id（交由数据库生成），{@code isNewEntity} 置 true。
 *
 * @author SAI
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("client_message")
public class ClientMessageEntity extends BaseEntity implements Persistable<Long> {

    /** 消息ID（自增） */
    @Id
    private Long id;

    /** 会话ID */
    private String conversationId;

    /** 归属用户ID（32位 MD5(username)） */
    private String userId;

    /** 角色 user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** token 数 */
    private Integer tokens;

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNewEntity;
    }
}
