package xsl.sai.agent.tool;

import cn.hutool.http.HtmlUtil;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索工具（声明式 {@link Tool}）。
 *
 * <p>使用 Bing 搜索结果页（{@code https://www.bing.com/search}）作为零依赖、无需 API Key 的检索后端；
 * {@code www.bing.com} 会按地域 302 到 {@code cn.bing.com} 等区域站点，由 {@link HttpClient} 自动跟随。
 * 若后续接入商业搜索 API，只需替换 {@link #doSearch(String, int)} 内部实现。
 * 结果仅返回标题/摘要/链接，供主 Agent 或研究类子 Agent 做信息检索。
 */
@Slf4j
@Component
public class WebSearchTool {

    /** Bing 搜索入口（地理重定向由 HttpClient 跟随） */
    private static final String BING_SEARCH = "https://www.bing.com/search?q=";

    /** 浏览器 UA：Bing 对非常规 UA 可能返回精简页，导致结果结构取不到 */
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /** 单条自然结果块：<li class="b_algo" …> … </li> */
    private static final Pattern RESULT_BLOCK = Pattern.compile(
            "<li class=\"b_algo\".*?</li>", Pattern.DOTALL);

    /** 结果标题与链接：<h2 …><a … href="URL" …>TITLE</a> */
    private static final Pattern RESULT_LINK = Pattern.compile(
            "<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    /** 结果摘要：标题之后的第一个 <p>…</p> */
    private static final Pattern RESULT_SNIPPET = Pattern.compile(
            "<p[^>]*>(.*?)</p>", Pattern.DOTALL);

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Tool(name = "web_search", description = "联网搜索公开网页，返回与查询相关的标题、摘要与链接列表。"
            + "适用于需要最新资讯、事实核查或外部知识的场景。")
    public String webSearch(
            RuntimeContext runtimeContext,
            @ToolParam(name = "query", required = true, description = "搜索关键词或问题") String query,
            @ToolParam(name = "max_results", required = false,
                    description = "返回结果条数上限，默认 5") Integer maxResults) {
        int limit = (maxResults == null || maxResults <= 0) ? 5 : Math.min(maxResults, 20);
        try {
            List<SearchHit> hits = doSearch(query, limit);
            if (hits.isEmpty()) {
                return "未找到与「" + query + "」相关的结果。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("搜索「").append(query).append("」得到 ").append(hits.size()).append(" 条结果：\n");
            for (int i = 0; i < hits.size(); i++) {
                SearchHit h = hits.get(i);
                sb.append(i + 1).append(". ").append(h.title()).append("\n")
                        .append("   ").append(h.snippet()).append("\n")
                        .append("   ").append(h.url()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("web_search 失败: {}", e.getMessage());
            return "搜索执行失败：" + e.getMessage();
        }
    }

    private List<SearchHit> doSearch(String query, int limit) throws Exception {
        String url = BING_SEARCH + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = resp.body();

        List<SearchHit> hits = new ArrayList<>();
        Matcher blocks = RESULT_BLOCK.matcher(body);
        while (blocks.find() && hits.size() < limit) {
            String block = blocks.group();
            Matcher lm = RESULT_LINK.matcher(block);
            if (!lm.find()) {
                continue;
            }
            String title = clean(lm.group(2));
            if (title.isEmpty()) {
                continue;
            }
            Matcher sm = RESULT_SNIPPET.matcher(block.substring(lm.end()));
            hits.add(new SearchHit(title, sm.find() ? clean(sm.group(1)) : "", lm.group(1)));
        }
        if (hits.isEmpty()) {
            log.warn("web_search 未解析到结果：status={}, bodyLength={}", resp.statusCode(), body.length());
        }
        return hits;
    }

    /** 去 HTML 标签 + 反转义实体（Bing 摘要里带 {@code &ensp;} / {@code &#0183;} 之类的日期分隔符） */
    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        return HtmlUtil.unescape(TAG.matcher(s).replaceAll("")).trim();
    }

    private record SearchHit(String title, String snippet, String url) {
    }
}
