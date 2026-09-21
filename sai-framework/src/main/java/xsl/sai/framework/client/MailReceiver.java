package xsl.sai.framework.client;

import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 邮件接收客户端 —— 基于 Jakarta Mail 的 IMAP/IMAPS 收件能力，对外暴露响应式接口。
 *
 * <p>特性：
 * <ul>
 *   <li>支持 IMAP(S) 协议拉取收件箱（INBOX）未读邮件；</li>
 *   <li>阻塞的 Jakarta Mail API 通过 {@code Schedulers.boundedElastic()} 包装为 {@link Mono}；</li>
 *   <li>IMAP 接收默认开启，仅在未配置 host（{@code spring.mail.receive.host} 为空）时优雅降级，
 *       返回空列表，不阻断调用方主流程；</li>
 *   <li>收发共用唯一一个邮箱：账号 / 授权码直接读取发送邮箱（{@code spring.mail.username} /
 *       {@code spring.mail.password}），接收端不单独配置账号；</li>
 *   <li>单封邮件解析出发件人 / 主题 / 正文 / 附件数等，封装为 {@link ReceivedMail} 供上层入库；</li>
 *   <li>配置前缀 {@code spring.mail.receive.*}（仅 IMAP 连接项），集中见 {@code application-framework.yml}。</li>
 * </ul>
 *
 * <p>去重由上层（邮件入库服务）基于 {@code Message-ID} 完成；本类仅负责“拉取 + 解析”，
 * 不做状态标记，避免“拉取成功但入库失败”导致邮件永久丢失。
 *
 * @DATE: 2026/9/17
 * @AUTHOR: SAI
 */
@Slf4j
@Component
public class MailReceiver {

    @Value("${spring.mail.receive.host:}")
    private String host;

    @Value("${spring.mail.receive.port:993}")
    private int port;

    /**
     * 收件账号：直接复用唯一邮箱账号（{@code spring.mail.username}），不单独配置。
     */
    @Value("${spring.mail.username:}")
    private String username;

    /**
     * 收件授权码：直接复用唯一邮箱授权码（{@code spring.mail.password}）。
     */
    @Value("${spring.mail.password:}")
    private String password;

    @Value("${spring.mail.receive.protocol:imaps}")
    private String protocol;

    @Value("${spring.mail.receive.folder:INBOX}")
    private String folderName;

    @Value("${spring.mail.receive.max:20}")
    private int maxFetch;

    /**
     * 拉取收件箱中的新邮件（已解析，未做去重）。
     *
     * @return 邮件列表（未配置或异常时返回空列表，不会抛异常阻断主流程）
     */
    public Mono<List<ReceivedMail>> fetchRecent() {
        if (host == null || host.isBlank()) {
            log.warn("[MailReceiver] 未配置 IMAP 服务器（spring.mail.receive.host 为空），跳过本次拉取");
            return Mono.just(List.of());
        }
        return Mono.fromCallable(this::doFetch)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(list -> log.info("[MailReceiver] 本次拉取到 {} 封邮件", list == null ? 0 : list.size()))
                .onErrorResume(e -> {
                    log.error("[MailReceiver] 拉取邮件失败 --> {}", e.getMessage(), e);
                    return Mono.just(List.of());
                });
    }

    /**
     * 邮件接收是否已就绪：需同时满足
     * <ul>
     *   <li>IMAP 服务器已配置（{@code spring.mail.receive.host} 非空）；</li>
     *   <li>收发共用邮箱的账号 / 授权码（{@code spring.mail.username} / {@code spring.mail.password}）非空。</li>
     * </ul>
     * 供上层（如 receive_mail 工具）在不触发实际连接的情况下判断能否接收。
     */
    public boolean isEnabled() {
        return StrUtil.isNotBlank(host)
                && StrUtil.isNotBlank(username) && StrUtil.isNotBlank(password);
    }

    /**
     * 阻塞式拉取（运行于 boundedElastic 线程）
     */
    private List<ReceivedMail> doFetch() throws Exception {
        // 收发共用唯一邮箱：账号/授权码直接来自 spring.mail
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            log.warn("[MailReceiver] 邮箱账号/授权码为空（spring.mail.username / spring.mail.password），跳过本次拉取");
            return List.of();
        }
        Properties props = new Properties();
        props.setProperty("mail.store.protocol", protocol);
        props.setProperty("mail." + protocol + ".host", host);
        props.setProperty("mail." + protocol + ".port", String.valueOf(port));
        props.setProperty("mail." + protocol + ".ssl.enable", "true");
        // 部分 IMAP 服务（如 QQ）要求身份校验，关闭才允许 fetch
        props.setProperty("mail." + protocol + ".auth", "true");

        Session session = Session.getInstance(props);
        List<ReceivedMail> result = new ArrayList<>();
        try (Store store = session.getStore(protocol)) {
            store.connect(host, username, password);
            try (Folder folder = store.getFolder(folderName)) {
                if (!folder.exists()) {
                    log.warn("[MailReceiver] 文件夹不存在 --> {}", folderName);
                    return result;
                }
                folder.open(Folder.READ_WRITE);
                // 取最新 maxFetch 封，从末尾向前遍历（末尾为最新）
                int total = folder.getMessageCount();
                int start = Math.max(1, total - maxFetch + 1);
                for (int i = start; i <= total; i++) {
                    try {
                        Message msg = folder.getMessage(i);
                        if (msg == null) {
                            continue;
                        }
                        // 跳过已读，仅处理未读（去重由入库层基于 Message-ID 兜底）
                        if (msg.getFlags().contains(Flags.Flag.SEEN)) {
                            continue;
                        }
                        result.add(parse(msg));
                    } catch (Exception e) {
                        log.warn("[MailReceiver] 解析第 {} 封邮件失败，跳过 --> {}", i, e.getMessage());
                    }
                }
            }
        }
        return result;
    }

    /**
     * 解析单封邮件为 {@link ReceivedMail}
     */
    private ReceivedMail parse(Message msg) throws Exception {
        String messageId = getHeader(msg, "Message-ID");
        String fromAddr = "";
        String fromName = "";
        Address[] from = msg.getFrom();
        if (from != null && from.length > 0) {
            if (from[0] instanceof InternetAddress ia) {
                fromAddr = ia.getAddress() == null ? "" : ia.getAddress();
                fromName = ia.getPersonal() == null ? "" : ia.getPersonal();
            } else {
                fromAddr = from[0].toString();
            }
        }
        String toAddr = "";
        Address[] to = msg.getRecipients(Message.RecipientType.TO);
        if (to != null && to.length > 0) {
            toAddr = to[0].toString();
        }
        String subject = msg.getSubject();
        LocalDateTime sentDate = msg.getSentDate() == null ? null
                : LocalDateTime.ofInstant(msg.getSentDate().toInstant(), ZoneId.systemDefault());
        String content = getPlainText(msg);
        int attachCount = countAttachments(msg);
        return new ReceivedMail(
                messageId,
                fromAddr,
                fromName,
                toAddr,
                subject,
                content,
                attachCount > 0,
                attachCount,
                sentDate
        );
    }

    /**
     * 递归提取纯文本正文
     */
    private String getPlainText(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content == null ? "" : content.toString();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            int count = mp.getCount();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                Part bodyPart = mp.getBodyPart(i);
                if (bodyPart.isMimeType("text/plain")) {
                    Object c = bodyPart.getContent();
                    if (c != null) {
                        sb.append(c).append("\n");
                    }
                } else if (bodyPart.isMimeType("multipart/*")) {
                    sb.append(getPlainText(bodyPart));
                }
                // 附件（有 disposition=attachment 或 fileName）不参与正文提取
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * 递归统计附件数量
     */
    private int countAttachments(Part part) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            int total = 0;
            for (int i = 0; i < mp.getCount(); i++) {
                Part bp = mp.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(bp.getDisposition()) || bp.getFileName() != null) {
                    total++;
                } else if (bp.isMimeType("multipart/*")) {
                    total += countAttachments(bp);
                }
            }
            return total;
        }
        return 0;
    }

    private String getHeader(Message msg, String name) {
        try {
            String[] vals = msg.getHeader(name);
            return (vals != null && vals.length > 0) ? vals[0] : "";
        } catch (MessagingException e) {
            return "";
        }
    }

    /**
     * 单封解析后的邮件（传输对象，不含持久化字段）
     *
     * @param messageId     邮件 Message-ID（去重键）
     * @param fromAddr      发件人邮箱
     * @param fromName      发件人显示名
     * @param toAddr        收件人
     * @param subject       主题
     * @param content       纯文本正文
     * @param hasAttachment 是否有附件
     * @param attachCount   附件数量
     * @param sentDate      邮件发送时间
     */
    public record ReceivedMail(
            String messageId,
            String fromAddr,
            String fromName,
            String toAddr,
            String subject,
            String content,
            boolean hasAttachment,
            int attachCount,
            LocalDateTime sentDate
    ) {
    }
}
