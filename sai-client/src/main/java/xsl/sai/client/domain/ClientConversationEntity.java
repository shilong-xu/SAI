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
 * 会话列表领域实体（client_ 模块）
 * <p>使用 Spring Data R2DBC 映射，简单 CRUD 优先走 {@code ConversationMapper} 自带方法；
 * 复杂自定义 SQL（如关联统计消息数）才使用 {@code R2dbcClient}。
 *
 * <p>实现 {@link Persistable}：通过 {@code isNewEntity} 标记区分 INSERT / UPDATE，
 * 避免手动预生成主键（32 位 UUID）被误判为“已存在”而生成 UPDATE 语句。
 *
 * @author SAI
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("client_conversation")
public class ClientConversationEntity extends BaseEntity implements Persistable<String> {

    /** 会话ID（32位字符串主键） */
    @Id
    private String id;

    /** 归属用户ID（32位 MD5(username)） */
    private String userId;

    /** 会话标题 */
    private String title;

    /** 使用的模型 */
    private String model;

    /** 是否置顶 0否 1是 */
    private Integer isPinned;

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNewEntity;
    }
}
