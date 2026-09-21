package xsl.sai.framework.config;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import xsl.sai.framework.config.AccountProperties.Account;
import xsl.sai.framework.pojo.bo.RequestInfoBO;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 账户提供者：启动时读取 {@link AccountProperties} 中配置的账户，
 * 供登录校验与「JWT（胡图 JWTUtil）」的生成 / 校验使用。
 *
 * <p>个人自用场景：账户密码完全由配置文件维护，无需数据库；无用户概念。
 * token 采用胡图 JWTUtil 生成的自包含 JWT（载荷含账号 username 与用户姓名 displayName），
 * 服务端只需校验签名 + 过期，无需保存任何 token 状态。
 *
 * @author XSL (适配 SAI 个人工具项目)
 * @date 2026-07-14
 */
@Slf4j
@Component
public class AccountProvider {

    private final AccountProperties accountProperties;

    /** JWT 签名密钥（胡图 JWTUtil，HMAC 签名），由 sai.auth.jwt-secret 注入 */
    private String jwtSecret;

    /** username -> 账户信息（明文对比用） */
    private volatile Map<String, AccountInfo> accountMap = Collections.emptyMap();

    public AccountProvider(AccountProperties accountProperties) {
        this.accountProperties = accountProperties;
    }

    @PostConstruct
    public void init() {
        if (accountProperties.getJwtSecret() == null || accountProperties.getJwtSecret().isBlank()) {
            this.jwtSecret = "sai-default-jwt-secret-please-change";
            log.warn("未配置 sai.auth.jwt-secret，使用默认密钥（仅限本地开发，生产环境务必修改为随机长字符串）");
        } else {
            this.jwtSecret = accountProperties.getJwtSecret();
        }
        Map<String, AccountInfo> map = new LinkedHashMap<>();
        for (Account acc : accountProperties.getAccounts()) {
            if (acc.getUsername() == null || acc.getUsername().isBlank()) {
                continue;
            }
            if (acc.getPassword() == null || acc.getPassword().isBlank()) {
                log.warn("账户 [{}] 未配置密码，已跳过", acc.getUsername());
                continue;
            }
            String displayName = acc.getDisplayName() == null ? acc.getUsername() : acc.getDisplayName();
            AccountInfo info = new AccountInfo(acc.getUsername(), acc.getPassword(), displayName);
            map.put(acc.getUsername(), info);
        }
        this.accountMap = map;
        if (map.isEmpty()) {
            log.warn("未配置任何有效账户（sai.auth.accounts），登录功能将不可用！");
        } else {
            log.info("已加载 {} 个账户：{}", map.size(), map.keySet());
        }
    }

    /**
     * 校验账号密码（明文对比）
     *
     * @return 校验通过返回账户信息，否则返回 empty
     */
    public Optional<AccountInfo> verify(String username, String rawPassword) {
        AccountInfo info = accountMap.get(username);
        if (info == null || rawPassword == null) {
            return Optional.empty();
        }
        return rawPassword.equals(info.getPassword()) ? Optional.of(info) : Optional.empty();
    }

    /**
     * 生成 JWT（胡图 JWTUtil，HMAC 签名）。
     * 载荷携带 username（账号）与 displayName（用户姓名），并设置过期时间，
     * 实现「无状态、自包含」的令牌：服务端只需校验签名 + 过期，无需保存 token。
     */
    public String generateToken(String username, String displayName) {
        long expireMs = System.currentTimeMillis()
                + (accountProperties.getTokenExpireMinutes() == null ? 1440L : accountProperties.getTokenExpireMinutes())
                * 60_000L;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("displayName", displayName == null ? username : displayName);
        payload.put("exp", expireMs);
        return JWTUtil.createToken(payload, jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验请求携带的 JWT 是否合法：签名有效（胡图 JWTUtil 验签）+ 未过期（JWTValidator 校验 exp）。
     */
    public boolean isValidToken(String token) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        try {
            if (!JWTUtil.verify(token, jwtSecret.getBytes(StandardCharsets.UTF_8))) {
                return false;
            }
            JWTValidator.of(token).validateDate();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Optional<AccountInfo> getByUsername(String username) {
        return Optional.ofNullable(accountMap.get(username));
    }

    public Optional<AccountInfo> getByToken(String token) {
        try {
            if (!isValidToken(token)) {
                return Optional.empty();
            }
            JWT jwt = JWTUtil.parseToken(token);
            String username = (String) jwt.getPayload("username");
            return getByUsername(username);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 由 JWT 解析并构造 {@link RequestInfoBO}，写入 Reactor 上下文供下游取当前用户。
     * <ul>
     *   <li>userId：MD5(username)（32位，与业务表 user_id 一致）</li>
     *   <li>identityId：即 token 本身</li>
     *   <li>username：登录用户名（账号）</li>
     *   <li>displayName：用户姓名</li>
     * </ul>
     */
    public Optional<RequestInfoBO> buildRequestInfoByToken(String token) {
        try {
            if (!isValidToken(token)) {
                return Optional.empty();
            }
            JWT jwt = JWTUtil.parseToken(token);
            String username = (String) jwt.getPayload("username");
            if (StrUtil.isBlank(username)) {
                return Optional.empty();
            }
            Object dn = jwt.getPayload("displayName");
            String displayName = dn == null ? username : String.valueOf(dn);
            RequestInfoBO bo = new RequestInfoBO();
            RequestInfoBO.UserInfo ui = new RequestInfoBO.UserInfo();
            ui.setUserId(DigestUtil.md5Hex(username));
            ui.setIdentityId(token);
            ui.setUsername(username);
            ui.setDisplayName(displayName);
            bo.setUserInfo(ui);
            return Optional.of(bo);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public int count() {
        return accountMap.size();
    }

    /** 账户信息（个人自用，密码以明文缓存，仅做对比） */
    @lombok.Getter
    public static class AccountInfo {
        private final String username;
        private final String password;
        private final String displayName;

        public AccountInfo(String username, String password, String displayName) {
            this.username = username;
            this.password = password;
            this.displayName = displayName;
        }
    }
}
