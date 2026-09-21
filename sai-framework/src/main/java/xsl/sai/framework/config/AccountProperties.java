package xsl.sai.framework.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 账户配置属性。
 *
 * <p>账户与密码在配置文件中维护（个人自用工具，无需数据库表）。
 * 密码为明文配置，启动时由 {@code AccountProvider} 统一做 BCrypt 哈希缓存。
 *
 * <p>配置示例（application.yml）：
 * <pre>
 * sai:
 *   auth:
 *     accounts:
 *       - username: admin
 *         password: your-password
 *         display-name: Admin
 * </pre>
 *
 * <p>鉴权采用注解驱动：默认所有 controller 端点都需令牌校验，
 * 只有标注 {@code @NoAuth} 的接口/方法（如登录、页面渲染）才放行，
 * 无需在此维护路径白名单。详见 {@code xsl.sai.framework.handler.AuthWebFilter}。
 *
 * @author XSL (适配 SAI 个人工具项目)
 * @date 2026-07-14
 */
@Data
@Component
@ConfigurationProperties(prefix = "sai.auth")
public class AccountProperties {

    /** 账户列表 */
    private List<Account> accounts = new ArrayList<>();

    /** 令牌有效期（分钟），默认 30 天 */
    private Long tokenExpireMinutes = 43200L;

    /** JWT 签名密钥（胡图 JWTUtil，HMAC 签名）。请配置为足够长且随机的字符串，生产务必修改 */
    private String jwtSecret;

    @Data
    public static class Account {
        /** 登录账号 */
        private String username;

        /** 登录密码（明文，启动时哈希） */
        private String password;

        /** 展示名（可选） */
        private String displayName;
    }
}
