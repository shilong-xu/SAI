package xsl.sai.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 网页抓取工具（声明式 {@link Tool}）。
 *
 * <p>抓取指定 URL 的 HTML 并转化为可读纯文本（去除脚本/样式/标签），
 * 供主 Agent 或研究类子 Agent 阅读网页正文、提取信息。
 * 仅做基本净化，不渲染 JS；对重度 SPA 页面可能提取不全。
 */
@Slf4j
@Component
public class WebFetchTool {

    private static final Pattern SCRIPT = Pattern.compile(
            "(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern BLANK = Pattern.compile("[ \\t]+");
    private static final Pattern NEWLINES = Pattern.compile("\\n{3,}");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Tool(name = "web_fetch", description = "抓取一个网页 URL 并将其正文转换为可读纯文本。"
            + "适用于阅读指定页面内容、提取文章或文档信息。不支持需要登录或重度 JS 渲染的页面。")
    public String webFetch(
            RuntimeContext runtimeContext,
            @ToolParam(name = "url", required = true,
                    description = "要抓取的网页地址（http/https）") String url,
            @ToolParam(name = "max_chars", required = false,
                    description = "返回文本的最大字符数，默认 8000，最大 40000") Integer maxChars) {
        int limit = (maxChars == null || maxChars <= 0) ? 8000 : Math.min(maxChars, 40000);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (compatible; SAI-Agent/1.0)")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                return "抓取失败，HTTP 状态码：" + resp.statusCode();
            }
            String text = toPlainText(resp.body());
            if (text.length() > limit) {
                text = text.substring(0, limit) + "\n...[已截断，原文更长]";
            }
            return text.isBlank() ? "页面未提取到可读文本（可能为纯脚本/图片页面）。" : text;
        } catch (Exception e) {
            log.warn("web_fetch 失败: {}", e.getMessage());
            return "抓取执行失败：" + e.getMessage();
        }
    }

    private static String toPlainText(String html) {
        if (html == null) return "";
        String s = SCRIPT.matcher(html).replaceAll(" ");
        s = TAG.matcher(s).replaceAll("\n");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        s = BLANK.matcher(s).replaceAll(" ");
        s = NEWLINES.matcher(s).replaceAll("\n\n");
        return s.strip();
    }
}
