package xsl.sai.page.controller;

import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import xsl.sai.framework.annotation.NoAuth;

/**
 * 页面路由控制器（服务端渲染，源码位于 sai-page 模块）。
 *
 * <p>架构：Thymeleaf（骨架/片段）+ Alpine.js（响应式）+ HTMX（片段懒加载）。
 * <ul>
 *   <li>{@code GET /} → 渲染 {@code index.html}（Alpine 根作用域外壳）。</li>
 *   <li>{@code GET /view/{name}} → 以 Thymeleaf 片段 {@code fragments/{name} :: {name}} 注入 {@code #content}。</li>
 * </ul>
 * 鉴权：本控制器整体 {@link NoAuth}，登录页始终可直接访问；真正的接口（/api/*）仍由
 * {@code AuthWebFilter} 强制校验 JWT。前端通过 Alpine 持有 JWT（localStorage），未登录时
 * 仅展示欢迎/登录覆盖层，登录后切换至工作台。
 *
 * @author SAI 前端迁移
 */
@Controller
@RequestMapping("/")
@NoAuth
public class PageController {

    /** 片段白名单，防止路径探测（仅允许以下视图名） */
    private static final Set<String> FRAGMENTS = Set.of(
            "welcome", "login", "chat", "kb", "email", "schedule", "schedule-log", "agent-schedule", "todo", "modals");

    /** 应用外壳 */
    @GetMapping
    public String index() {
        return "index";
    }

    /** 片段懒加载：返回 fragments/{name}.html 中名为 {name} 的片段 */
    @GetMapping("/view/{name}")
    public String view(@PathVariable("name") String name) {
        if (!FRAGMENTS.contains(name)) {
            // 未知片段一律回退登录，杜绝任意模板路径探测
            return "fragments/login :: login";
        }
        return "fragments/" + name + " :: " + name;
    }
}
