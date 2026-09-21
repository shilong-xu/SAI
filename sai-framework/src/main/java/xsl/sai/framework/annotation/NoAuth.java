package xsl.sai.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口/方法免登录鉴权。
 *
 * <p>{@link xsl.sai.framework.handler.AuthWebFilter} 会跳过带此注解的处理器。
 *
 * @author XSL (适配 SAI 个人工具项目)
 * @date 2026-07-14
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoAuth {
}
