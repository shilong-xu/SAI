package xsl.sai.framework.client;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地 Embeddings 服务客户端 —— 将文本转换为向量（float[]）。
 *
 * <p>默认对接 <b>Ollama bge-m3</b>（1024 维，多语言/中文友好），与参考实现
 * {@code com.rag.EmbeddingClient} 同构：
 * <ul>
 *   <li><b>单条</b>：POST {@code {base-url}/api/embeddings}，
 *       请求 {@code {"model":"bge-m3","prompt":"文本"}}，响应 {@code {"embedding":[...]}}</li>
 *   <li><b>批量</b>：POST {@code {base-url}/api/embed}，
 *       请求 {@code {"model":"bge-m3","input":["文本1","文本2"]}}，响应 {@code {"embeddings":[[...]]}}</li>
 *   <li><b>探活</b>：GET {@code {base-url}/api/tags}，检查目标模型是否已拉取</li>
 * </ul>
 *
 * <p>同时保留对其它格式的兼容（通过 {@code agentScope.embedding.format} 切换）：
 * <ul>
 *   <li><b>openai</b>：POST {@code {base-url}{api-path}}（默认 {@code /v1/embeddings}），
 *       请求 {@code {"input":"文本","model":"..."}}，响应 {@code {"data":[{"embedding":[...]}]}}</li>
 *   <li><b>tei</b>：请求 {@code {"inputs":["文本1","文本2"]}}，响应 {@code {"embeddings":[[...]]}}</li>
 *   <li><b>baserow</b>：请求 {@code {"texts":["文本1","文本2"]}}，响应 {@code {"embeddings":[[...]]}}</li>
 * </ul>
 * 注意：当 {@code format=ollama} 时 {@code api-path} 不生效（固定使用 Ollama 标准双端点）。
 *
 * @DATE: 2026/7/20
 * @AUTHOR: XSL
 */
@Slf4j
@Component
public class EmbeddingClient {

    /** Ollama 单条 / 批量标准端点 */
    private static final String OLLAMA_EMBED_ONE = "/api/embeddings";
    private static final String OLLAMA_EMBED_BATCH = "/api/embed";
    private static final String OLLAMA_TAGS = "/api/tags";
    /** 默认模型（与下方 @Value 默认保持一致） */
    private static final String DEFAULT_MODEL = "bge-m3";

    @Getter
    @Value("${agentScope.embedding.enabled:false}")
    private boolean enabled;

    /**
     * -- GETTER --
     * 当前配置的 embeddings 服务地址（用于诊断日志）
     */
    @Getter
    @Value("${agentScope.embedding.base-url:http://127.0.0.1:11434}")
    private String baseUrl;

    @Value("${agentScope.embedding.api-path:/api/embed}")
    private String apiPath;

    @Value("${agentScope.embedding.model:bge-m3}")
    private String model;

    /** 接口格式（已小写规范化）：ollama | openai | tei | baserow */
    @Value("${agentScope.embedding.format:ollama}")
    private String format;

    private String formatNormalized; // @PostConstruct 中赋值为 format.toLowerCase(Locale.ROOT)

    @Value("${agentScope.embedding.timeout-ms:30000}")
    private int timeoutMs;

    /** 期望向量维度（与知识库一致，用于校验；0 表示不校验） */
    @Value("${agentScope.embedding.vector-dimensions:1024}")
    private int vectorDimensions;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @PostConstruct
    void init() {
        this.formatNormalized = format.toLowerCase(Locale.ROOT);
    }

    private boolean isOllama() {
        return "ollama".equals(formatNormalized);
    }

    /**
     * 探活 —— 检查 embeddings 服务是否可用，且目标模型已加载。
     * 永不抛异常；仅 Ollama 格式下会真正校验模型是否在 {@code /api/tags} 列表中。
     */
    public boolean health() {
        if (!enabled) {
            return false;
        }
        if (!isOllama()) {
            // 非 Ollama：沿用一次最小连通性探针
            return !"disabled".equals(probe());
        }
        String url = StrUtil.removeSuffix(baseUrl, "/") + OLLAMA_TAGS;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofMillis(Math.min(timeoutMs, 5000)))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return false;
            }
            JSONObject root = JSONUtil.parseObj(resp.body());
            JSONArray models = root.getJSONArray("models");
            if (models == null) {
                return true; // 服务在线，但无法解析模型列表时也视为可用
            }
            String want = StrUtil.isNotBlank(model) ? model : DEFAULT_MODEL;
            for (Object m : models) {
                if (m instanceof JSONObject mo && want.equals(mo.getStr("name"))) {
                    return true;
                }
            }
            log.warn("Ollama 已在线，但未找到模型 {}（请先 `ollama pull {}`）", want, want);
            return false;
        } catch (Exception e) {
            log.debug("embedding health check 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 启动期连通性探针 —— 非破坏性（仅发一次最小请求），永不抛异常。
     * 返回可读状态，便于在日志中区分：
     * <ul>
     *   <li>{@code ok}：端点正常返回 2xx</li>
     *   <li>{@code responded(404)}：端口有响应但不是 embeddings 服务</li>
     *   <li>{@code unreachable(...)}：端口无监听 / 连接失败</li>
     *   <li>{@code disabled}：未启用</li>
     * </ul>
     */
    public String probe() {
        if (!enabled) {
            return "disabled";
        }
        String url = isOllama()
                ? StrUtil.removeSuffix(baseUrl, "/") + OLLAMA_EMBED_BATCH
                : StrUtil.removeSuffix(baseUrl, "/") + apiPath;
        try {
            String probeBody = isOllama()
                    ? buildOllamaBatch(List.of("ping"))
                    : buildRequest(List.of("ping"));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(Math.min(timeoutMs, 5000)))
                    .POST(HttpRequest.BodyPublishers.ofString(probeBody))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            return (code >= 200 && code < 300) ? "ok" : "responded(" + code + ")";
        } catch (Exception e) {
            return "unreachable(" + e.getClass().getSimpleName() + ")";
        }
    }

    /** 单条文本 → 向量 */
    public float[] embed(String text) {
        List<float[]> r = embed(List.of(text));
        return (r == null || r.isEmpty()) ? new float[0] : r.get(0);
    }

    /** 批量文本 → 向量列表（保持输入顺序） */
    public List<float[]> embed(List<String> texts) {
        if (!enabled) {
            throw new IllegalStateException(
                    "Embedding 客户端未启用，请在 application-agent.yml 设置 agentScope.embedding.enabled=true");
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        String url;
        String body;
        boolean ollamaSingle;
        if (isOllama()) {
            // Ollama 单条用 /api/embeddings，批量用 /api/embed（与参考实现一致）
            if (texts.size() == 1) {
                url = StrUtil.removeSuffix(baseUrl, "/") + OLLAMA_EMBED_ONE;
                body = buildOllamaOne(texts.get(0));
                ollamaSingle = true;
            } else {
                url = StrUtil.removeSuffix(baseUrl, "/") + OLLAMA_EMBED_BATCH;
                body = buildOllamaBatch(texts);
                ollamaSingle = false;
            }
        } else {
            url = StrUtil.removeSuffix(baseUrl, "/") + apiPath;
            body = buildRequest(texts);
            ollamaSingle = false;
        }

        log.debug("[Embedding] 调用 Embedding 服务 --> {}, format --> {}, model --> {}", url, format, model);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException("HTTP " + resp.statusCode() + " body=" + resp.body());
            }
            List<float[]> vecs = parseResponse(resp.body(), ollamaSingle);
            if (vectorDimensions > 0) {
                for (float[] v : vecs) {
                    if (v != null && v.length != vectorDimensions) {
                        log.warn("Embedding 维度不一致：期望 {}，实际 {}（请核对 embedding 模型，当前 {}）",
                                vectorDimensions, v.length, model);
                    }
                }
            }
            return vecs;
        } catch (Exception e) {
            String hint = isOllama()
                    ? "，请确认 Ollama 已启动(`ollama serve`) 且已拉取模型(`ollama pull " + model + "`)"
                    : "";
            log.error("[Embedding] 调用失败 (url --> {}): {}{}", url, e.getClass().getSimpleName(), hint, e);
            throw new RuntimeException("Embedding 调用失败: " + e.getClass().getSimpleName() + hint, e);
        }
    }

    // ==================== Ollama 请求构造（参考实现同构） ====================

    private String buildOllamaOne(String text) {
        JSONObject o = new JSONObject();
        if (StrUtil.isNotBlank(model)) {
            o.set("model", model);
        }
        o.set("prompt", text);
        return o.toString();
    }

    private String buildOllamaBatch(List<String> texts) {
        JSONObject o = new JSONObject();
        if (StrUtil.isNotBlank(model)) {
            o.set("model", model);
        }
        JSONArray arr = new JSONArray();
        arr.addAll(texts);
        o.set("input", arr);
        return o.toString();
    }

    // ==================== 通用请求构造（非 Ollama 格式） ====================

    private String buildRequest(List<String> texts) {
        JSONObject o = new JSONObject();
        switch (format.toLowerCase()) {
            case "baserow": {
                JSONArray arr = new JSONArray();
                arr.addAll(texts);
                o.set("texts", arr);
                break;
            }
            case "tei":
            case "tei-embed": {
                JSONArray inputs = new JSONArray();
                inputs.addAll(texts);
                o.set("inputs", inputs);
                break;
            }
            case "ollama":
                // 不会走到这里（ollama 在 embed 内已单独处理），保留以防直接调用
                return buildOllamaBatch(texts);
            case "openai":
            default: {
                if (StrUtil.isNotBlank(model)) {
                    o.set("model", model);
                }
                if (texts.size() == 1) {
                    o.set("input", texts.get(0));
                } else {
                    JSONArray arr = new JSONArray();
                    texts.forEach(arr::add);
                    o.set("input", arr);
                }
                break;
            }
        }
        return o.toString();
    }

    // ==================== 响应解析 ====================

    private List<float[]> parseResponse(String body, boolean ollamaSingle) {
        if (ollamaSingle) {
            // Ollama /api/embeddings 响应：{"embedding":[...]}
            JSONObject json = JSONUtil.parseObj(body);
            return List.of(toFloat(json.getJSONArray("embedding")));
        }
        JSONObject json = JSONUtil.parseObj(body);
        switch (formatNormalized) {
            case "baserow":
                return toVecList(json.getJSONArray("embeddings"));
            case "tei":
            case "tei-embed": {
                JSONArray arr = json.getJSONArray("embeddings");
                if (arr == null) {
                    arr = json.getJSONArray("data");
                }
                if (arr == null) {
                    arr = JSONUtil.parseArray(body);
                }
                return toVecList(arr);
            }
            case "ollama": {
                JSONArray arr = json.getJSONArray("embeddings");
                if (arr == null) {
                    arr = json.getJSONArray("embedding");
                }
                if (arr == null) {
                    arr = JSONUtil.parseArray(body);
                }
                return toVecList(arr);
            }
            case "openai":
            default: {
                JSONArray data = json.getJSONArray("data");
                List<float[]> res = new ArrayList<>();
                if (data != null) {
                    for (int i = 0; i < data.size(); i++) {
                        JSONObject item = data.getJSONObject(i);
                        res.add(toFloat(item.getJSONArray("embedding")));
                    }
                }
                return res;
            }
        }
    }

    private List<float[]> toVecList(JSONArray arr) {
        List<float[]> res = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                Object e = arr.get(i);
                if (e instanceof JSONArray ja) {
                    res.add(toFloat(ja));
                }
            }
        }
        return res;
    }

    private float[] toFloat(JSONArray arr) {
        if (arr == null) {
            return new float[0];
        }
        float[] v = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            Object o = arr.get(i);
            v[i] = (o instanceof Number n) ? n.floatValue() : Float.parseFloat(o.toString());
        }
        return v;
    }
}
