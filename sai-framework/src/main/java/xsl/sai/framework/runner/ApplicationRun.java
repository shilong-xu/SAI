package xsl.sai.framework.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * @DATE:  2024/5/19
 * @AUTHOR:  XSL
 *
 */
@Slf4j
@Component
public class ApplicationRun implements ApplicationRunner {

    @Autowired
    private Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        log.info("服务初始化并启动完成 -> {}", resolve("spring.application.name", "未配置"));
        log.info("端口号 --> {}", resolve("server.port", "8080"));

        // ===== 当前配置文件关键摘要 =====
        log.info("================ 配置文件摘要 ================");

        // ① 对话大模型
        log.info("[大模型] modelName --> {}", resolve("agentScope.models.modelName", "未配置"));
        log.info("[大模型] apiBase   --> {}", resolve("agentScope.models.apiBase", "未配置"));
        log.info("[大模型] apiKey    --> {}", mask(resolve("agentScope.models.apiKey", null)));
        log.info("[大模型] temperature --> {}, maxTokens --> {}",
                resolve("agentScope.models.temperature", "0.7"),
                resolve("agentScope.models.maxTokens", "2000"));

        // ② 数据库（R2DBC）
        log.info("[数据库] url      --> {}", resolve("spring.r2dbc.url", "未配置"));
        log.info("[数据库] username --> {}", resolve("spring.r2dbc.username", "未配置"));
        log.info("[数据库] password --> {}", mask(resolve("spring.r2dbc.password", null)));

        // ③ Redis
        log.info("[Redis] host --> {}, port --> {}, database --> {}",
                resolve("spring.data.redis.host", "未配置"),
                resolve("spring.data.redis.port", "6379"),
                resolve("spring.data.redis.database", "0"));
        log.info("[Redis] password  --> {}, failFast --> {}",
                mask(resolve("spring.data.redis.password", null)),
                resolve("spring.data.redis.fail-fast", "false"));

        // ④ 向量化模型（Embedding）
        log.info("[向量化] enabled --> {}, model --> {}, format --> {}",
                resolve("agentScope.embedding.enabled", ""),
                resolve("agentScope.embedding.model", ""),
                resolve("agentScope.embedding.format", ""));
        log.info("[向量化] base-url --> {}, vector-dimensions --> {}",
                resolve("agentScope.embedding.base-url", ""),
                resolve("agentScope.embedding.vector-dimensions", ""));

        log.info("=============================================");
    }

    /** 从 Spring Environment 解析配置项，缺失时回退默认值 */
    private String resolve(String key, String def) {
        String v = environment.getProperty(key);
        return v != null ? v : (def != null ? def : "未配置");
    }

    /** 敏感信息脱敏：未配置返回"未配置"，否则仅保留长度提示，避免密钥泄露到日志 */
    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "未配置";
        }
        return "******(len=" + value.length() + ")";
    }

}
