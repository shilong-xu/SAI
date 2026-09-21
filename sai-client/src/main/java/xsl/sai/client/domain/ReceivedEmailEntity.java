package xsl.sai.client.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import xsl.sai.framework.base.BaseEntity;

import java.time.LocalDateTime;

/**
 * 邮件领域实体（收 + 发统一记录）
 *
 * <p>用 {@code direction} 区分方向：{@code 0}-接收（定时任务从收件箱拉取入库）、
 * {@code 1}-发送（Agent 调用 {@code send_mail} 成功后入库留痕）。
 *
 * <p>使用自增 {@code BIGINT} 主键，{@code id == null} 即新记录，
 * {@code ReactiveCrudRepository.save()} 自动走 INSERT。
 *
 * <p>本实体仅映射业务列；原 {@code status} / {@code process_result} / {@code embedding_vec}
 * 三列已从表中移除（邮件不再做 Agent 处理与向量化）。
 *
 * @author SAI
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("received_email")
public class ReceivedEmailEntity extends BaseEntity {

    /** 主键ID（自增） */
    @Id
    private Long id;

    /** 归属用户（预留，当前不隔离） */
    @Column("user_id")
    private String userId;

    /** 邮件方向 0-接收 1-发送 */
    @Column("direction")
    private Integer direction;

    /** 邮件 Message-ID（去重用） */
    @Column("message_id")
    private String messageId;

    /** 发件人邮箱 */
    @Column("from_addr")
    private String fromAddr;

    /** 发件人显示名 */
    @Column("from_name")
    private String fromName;

    /** 收件人邮箱 */
    @Column("to_addr")
    private String toAddr;

    /** 邮件主题 */
    @Column("subject")
    private String subject;

    /** 邮件正文（接收=纯文本；发送=原始正文，可能为 HTML） */
    @Column("content")
    private String content;

    /** Agent 处理结果摘要 */
    @Column("summary")
    private String summary;

    /** 是否有附件（0否 1是） */
    @Column("has_attachment")
    private Boolean hasAttachment;

    /** 附件数量 */
    @Column("attach_count")
    private Integer attachCount;

    /** 邮件时间（接收=邮件头 Date；发送=发送时刻） */
    @Column("received_at")
    private LocalDateTime receivedAt;
}
