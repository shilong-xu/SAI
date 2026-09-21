package xsl.sai.framework.runner;

import cn.hutool.core.io.IoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xsl.sai.framework.client.R2dbcClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据库初始化开关：项目启动后检查 {@code sql/sai.sql} 定义的表是否已建，
 * 仅对「缺失的表」自动执行脚本中的对应建表语句（不触碰已存在表，避免数据丢失）。
 *
 * <p>开关由配置 {@code sai.schema.auto-init} 控制（默认开启）；
 * 脚本位置可经 {@code sai.schema.sql-location} 覆盖（支持 classpath: / file:）。
 *
 * @author SAI
 */
@Slf4j
@Component
public class SchemaInitRunner {

    /** sai.sql 期望初始化的表（与脚本保持一致） */
    private static final Set<String> EXPECTED_TABLES = Set.of(
            "client_conversation", "client_message", "knowledge_base", "knowledge_chunk",
            "notify_message", "agent_memory");

    private static final Pattern DROP = Pattern.compile("(?i)DROP\\s+TABLE\\s+IF\\s+EXISTS\\s+`?(\\w+)`?");
    private static final Pattern CREATE = Pattern.compile("(?i)CREATE\\s+TABLE\\s+`?(\\w+)`?");

    private final R2dbcClient r2dbcClient;
    private final ResourceLoader resourceLoader;
    private final boolean autoInit;
    private final String sqlLocation;

    @Autowired
    public SchemaInitRunner(R2dbcClient r2dbcClient,
                            ResourceLoader resourceLoader,
                            @Value("${sai.schema.auto-init:true}") boolean autoInit,
                            @Value("${sai.schema.sql-location:classpath:sql/sai.sql}") String sqlLocation) {
        this.r2dbcClient = r2dbcClient;
        this.resourceLoader = resourceLoader;
        this.autoInit = autoInit;
        this.sqlLocation = sqlLocation;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!autoInit) {
            log.info("[SchemaInit] 自动初始化已关闭 (sai.schema.auto-init=false)，跳过");
            return;
        }
        String sql;
        try {
            sql = readSql();
        } catch (IOException e) {
            log.error("[SchemaInit] 读取初始化 SQL 失败 ({}) --> {}", sqlLocation, e.getMessage());
            return;
        }
        List<Statement> stmts = parse(sql);
        checkAndInit(stmts).subscribe(
                null,
                e -> log.error("[SchemaInit] 初始化过程异常 --> {}", e.getMessage())
        );
    }

    /** 查询已存在的目标表（小写表名集合） */
    private Mono<Set<String>> queryExistingTables() {
        String[] tables = EXPECTED_TABLES.toArray(new String[0]);
        Map<String, Object> params = new HashMap<>();
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < tables.length; i++) {
            if (i > 0) in.append(",");
            String k = "t" + i;
            in.append(":").append(k);
            params.put(k, tables[i]);
        }
        String sql = "SELECT TABLE_NAME FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (" + in + ")";
        return r2dbcClient.queryList(sql, params, Map.class)
                .map(row -> {
                    Object v = row.get("TABLE_NAME");
                    return v == null ? "" : v.toString().toLowerCase();
                })
                .collect(Collectors.toSet())
                .defaultIfEmpty(Collections.emptySet());
    }

    /** 比对已存在表，仅对缺失表执行脚本中对应的 DROP+CREATE 语句 */
    private Mono<Void> checkAndInit(List<Statement> stmts) {
        return queryExistingTables().flatMap(existing -> {
            Set<String> missing = new HashSet<>(EXPECTED_TABLES);
            missing.removeAll(existing);
            if (missing.isEmpty()) {
                log.info("[SchemaInit] 数据库表已完整初始化，无需执行脚本 (共 {} 张表)", EXPECTED_TABLES.size());
                return Mono.empty();
            }
            log.warn("[SchemaInit] 检测到缺失表 {}，将自动执行 {} 补充建表", missing, sqlLocation);
            List<Mono<Long>> jobs = new ArrayList<>();
            for (Statement s : stmts) {
                if (s.table != null && missing.contains(s.table.toLowerCase())) {
                    jobs.add(r2dbcClient.execute(s.sql, Map.of())
                            .doOnSuccess(r -> log.info("[SchemaInit] ✓ 执行成功 --> {}", summarize(s.sql)))
                            .onErrorResume(e -> {
                                log.error("[SchemaInit] ✗ 执行失败 --> {} -> {}", summarize(s.sql), e.getMessage());
                                return Mono.empty();
                            }));
                }
            }
            return jobs.isEmpty() ? Mono.empty() : Flux.concat(jobs).then();
        });
    }

    /** 解析脚本为单条语句，仅保留 DROP/CREATE TABLE（跳过注释与 SET 指令） */
    private List<Statement> parse(String sql) {
        List<Statement> result = new ArrayList<>();
        String noBlock = sql.replaceAll("/\\*.*?\\*/", " ");
        for (String part : noBlock.split(";")) {
            String cleaned = stripLineComments(part).trim();
            if (cleaned.isEmpty()) continue;
            if (cleaned.toLowerCase().startsWith("set ")) continue; // SET NAMES / SET FOREIGN_KEY_CHECKS
            Matcher m;
            if ((m = CREATE.matcher(cleaned)).find()) {
                result.add(new Statement(m.group(1).toLowerCase(), cleaned));
            } else if ((m = DROP.matcher(cleaned)).find()) {
                result.add(new Statement(m.group(1).toLowerCase(), cleaned));
            }
        }
        return result;
    }

    private String stripLineComments(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            if (line.trim().startsWith("--")) continue;
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private String readSql() throws IOException {
        Resource resource = resourceLoader.getResource(sqlLocation);
        if (!resource.exists()) {
            throw new IOException("未找到初始化 SQL 资源: " + sqlLocation);
        }
        try (InputStream is = resource.getInputStream()) {
            return IoUtil.readUtf8(is);
        }
    }

    private String summarize(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    /** 单条建表语句描述 */
    private static final class Statement {
        final String table;  // 小写表名
        final String sql;

        Statement(String table, String sql) {
            this.table = table;
            this.sql = sql;
        }
    }
}
