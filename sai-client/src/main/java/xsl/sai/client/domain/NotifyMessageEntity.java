package xsl.sai.client.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import xsl.sai.framework.base.BaseEntity;

/**
 * 站内消息通知领域实体（表 notify_message）。
 *
 * <p>消息按用户隔离：{@code user_id} 必有值（广播类事件走 SseClient 直接推送，不落本表），
 * 这样"已读"状态才有明确归属。
 *
 * <p>实现 {@link Persistable}：通过 {@code isNewEntity} 标记区分 INSERT / UPDATE
 * （同项目其他实体惯例）。
 *
 * @author SAI
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("notify_message")
public class NotifyMessageEntity extends BaseEntity implements Persistable<Long> {

    /** 主键ID（自增） */
    @Id
    private Long id;

    /** 接收用户ID */
    @Column("user_id")
    private String userId;

    /** 消息类型：info / success / warn / error */
    @Column("type")
    private String type;

    /** 消息标题 */
    @Column("title")
    private String title;

    /** 消息内容 */
    @Column("content")
    private String content;

    /** 点击跳转（前端视图名） */
    @Column("link")
    private String link;

    /** 是否已读（0未读 1已读）；tinyint(1) 由 R2DBC 读为 Boolean，故用包装类型 */
    @Column("read_flag")
    private Boolean readFlag;

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNewEntity;
    }
}
