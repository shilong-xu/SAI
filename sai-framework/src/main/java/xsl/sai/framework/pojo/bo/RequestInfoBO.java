package xsl.sai.framework.pojo.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前请求的用户信息载体。
 *
 * <p>针对 SAI「个人自用工具」场景裁剪：去掉多租户 / 角色 / 部门 / 菜单等字段，
 *   仅保留账户密码登录所需的身份标识。
 *
 * @author XSL (移植自 xsl-framework，适配 SAI)
 * @date 2026-07-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestInfoBO {

    /**
     * 请求ID（链路追踪用）
     */
    private String requestId;

    /**
     * 请求时间
     */
    private String requestTime;

    /**
     * 本次请求的用户信息
     */
    private UserInfo userInfo;


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {

        /**
         * 用户ID（账户主键）
         */
        private String userId;

        /**
         * 身份ID（用于 Redis 登录态 key：login:{identityId}）
         */
        private String identityId;

        /**
         * 用户账号（登录名）
         */
        private String username;

        /**
         * 用户姓名（displayName，来自登录账号配置）
         */
        private String displayName;

    }

}
