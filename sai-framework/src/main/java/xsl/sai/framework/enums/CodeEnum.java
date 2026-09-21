package xsl.sai.framework.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 框架接口返回状态码（个人工具裁剪版）。
 *
 * @author XSL (适配 SAI 个人工具项目)
 * @date 2026-07-14
 */
@Getter
@AllArgsConstructor
public enum CodeEnum {

    /** 操作成功 */
    SUCCESS(200, "操作成功"),

    /** 参数错误 */
    BAD_REQUEST(400, "参数错误"),

    /** 未登录或登录已过期 */
    UNAUTHORIZED(401, "未登录或登录已过期"),

    /** 系统内部错误 */
    INTERNAL_ERROR(500, "系统繁忙，请稍后再试"),

    /** 用户名或密码错误 */
    LOGIN_FAILED(1001, "用户名或密码错误"),

    /** Token 无效或已过期 */
    TOKEN_INVALID(1003, "登录状态已失效，请重新登录"),

    /** 未登录 */
    NOT_LOGIN(1005, "请先登录"),

    /** 参数缺失 */
    PARAM_MISSING(2001, "参数缺失"),

    /** 数据不存在 */
    DATA_NOT_FOUND(3001, "数据不存在"),
    ;

    private final int code;
    private final String message;
}
