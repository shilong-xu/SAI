package xsl.sai.client.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录返回
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthVO {
    /** 令牌 */
    private String token;
    /** 账号 */
    private String username;
    /** 展示名 */
    private String displayName;
}
