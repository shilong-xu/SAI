package xsl.sai.framework.client;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xsl.sai.framework.holder.SpringHolder;

import java.io.File;
import java.util.List;

/**
 * 邮件发送客户端 —— 基于 Spring {@link JavaMailSender} 封装，对外暴露响应式接口。
 *
 * <p>特性：
 * <ul>
 *   <li>支持纯文本 / HTML 正文、多个收件人、抄送、附件；</li>
 *   <li>阻塞的 JavaMail API 通过 {@code Schedulers.boundedElastic()} 包装为 {@link Mono}；</li>
 *   <li>邮件服务未配置（{@code spring.mail.host} 为空）时优雅降级，发送返回失败 {@link Mono} 不阻断主流程；</li>
 *   <li>配置前缀 {@code spring.mail}（host / username / password / ...），发件人显示名见 {@code spring.mail.from}，集中见 {@code application-framework.yml}。</li>
 * </ul>
 *
 * @DATE: 2026/8/4
 * @AUTHOR: SAI
 */
@Slf4j
@Component
public class MailClient {

    private static JavaMailSender mailSender;
    private static InternetAddress fromAddress;
    private static boolean enabled;

    /** 发件人显示名：标准 spring.mail 无此字段，单独从 spring.mail.from 读取，缺省同 username */
    @Value("${spring.mail.from:}")
    private String fromValue;

    /** 在 Bean 初始化后惰性装配，未配置邮件时不阻断应用启动 */
    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            MailProperties props = SpringHolder.getBean(MailProperties.class);
            enabled = props.getHost() != null && !props.getHost().isBlank();
            mailSender = SpringHolder.getBean(JavaMailSender.class);
            // 发件地址必须是真实邮箱（QQ 等服务器要求 MAIL FROM 与登录账号一致，否则 502）；
            // spring.mail.from 仅作为“显示名”附加，不能代替邮箱地址作为信封发件人。
            String displayName = (fromValue != null && !fromValue.isBlank()) ? fromValue : props.getUsername();
            fromAddress = new InternetAddress(props.getUsername(), displayName, "UTF-8");
            if (!enabled) {
                log.warn("邮件服务未配置（spring.mail.host 为空），MailClient 将降级为不可用，发送调用会返回失败 Mono");
            }
        } catch (Exception e) {
            enabled = false;
            mailSender = null;
            log.warn("邮件客户端初始化失败，MailClient 将降级为不可用：{}", e.getMessage());
        }
    }

    private static void assertReady() {
        if (!enabled || mailSender == null) {
            throw new IllegalStateException("邮件服务未启用（请检查 spring.mail.host / username / password 等配置）");
        }
    }

    /**
     * 发送简单文本邮件
     *
     * @param to      收件人（多个用逗号分隔，或传入单封）
     * @param subject 主题
     * @param content 正文（纯文本）
     * @return Mono<Void> 发送完成信号
     */
    public Mono<Void> sendText(String to, String subject, String content) {
        return send(to, null, subject, content, false, null);
    }

    /**
     * 发送 HTML 邮件
     *
     * @param to      收件人（多个用逗号分隔）
     * @param subject 主题
     * @param content HTML 正文
     * @return Mono<Void> 发送完成信号
     */
    public Mono<Void> sendHtml(String to, String subject, String content) {
        return send(to, null, subject, content, true, null);
    }

    /**
     * 发送带附件的邮件
     *
     * @param to       收件人（多个用逗号分隔）
     * @param subject  主题
     * @param content  正文（HTML）
     * @param cc       抄送（可空）
     * @param html     是否 HTML 正文
     * @param attachments 附件文件列表（可空）
     * @return Mono<Void> 发送完成信号
     */
    public Mono<Void> send(String to, String cc, String subject, String content,
                           boolean html, List<File> attachments) {
        return Mono.fromRunnable(() -> {
                    assertReady();
                    try {
                        MimeMessage message = mailSender.createMimeMessage();
                        // 第二个参数 true 表示 multipart（支持附件）
                        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                        helper.setFrom(fromAddress);
                        helper.setTo(split(to));
                        if (cc != null && !cc.isBlank()) {
                            helper.setCc(split(cc));
                        }
                        helper.setSubject(subject);
                        helper.setText(content, html);
                        if (attachments != null) {
                            for (File file : attachments) {
                                if (file != null && file.exists()) {
                                    FileSystemResource resource = new FileSystemResource(file);
                                    helper.addAttachment(file.getName(), resource);
                                }
                            }
                        }
                        mailSender.send(message);
                        log.info("邮件发送成功 --> to={}, subject={}", to, subject);
                    } catch (Exception e) {
                        log.error("邮件发送失败 --> to={}, subject={} : {}", to, subject, e.getMessage(), e);
                        throw new RuntimeException("邮件发送失败：" + e.getMessage(), e);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 发件邮箱地址（{@code spring.mail.username}）；邮件服务未配置时为 {@code null}
     */
    public String getFromAddress() {
        return fromAddress == null ? null : fromAddress.getAddress();
    }

    /**
     * 发件人显示名（{@code spring.mail.from}，缺省同 username）；未配置时为 {@code null}
     */
    public String getFromName() {
        if (fromAddress == null) {
            return null;
        }
        try {
            return fromAddress.getPersonal();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断邮件服务是否可用
     */
    public boolean isEnabled() {
        return enabled && mailSender != null;
    }

    private static String[] split(String value) {
        return value.split("[,;\\s]+");
    }
}
