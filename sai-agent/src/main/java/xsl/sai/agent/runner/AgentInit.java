package xsl.sai.agent.runner;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 工作区初始化器
 *
 * <p>启动时（在 {@code harnessAgent} Bean 构建主 Agent 之前）优先检查工作区
 * （{@code agentScope.workspace}，默认 {@code .agentscope/workspace}）中是否具备
 * Agent 运行所需的必要文件：这些文件与 {@code sai-agent/src/main/resources} 下的一致，
 * <b>但不包括 {@code application-agent.yml}</b>（那是 Spring Boot 配置，不属于工作区内容）。
 *
 * <p><b>必要文件不再需要手动维护清单</b>：子智能体、技能、知识库三类内容通过
 * {@link ResourcePatternResolver} 在 classpath 中<b>自动扫描</b>（{@code subagents/**}、
 * {@code skills/**}、{@code knowledge/**}），新增/删除任一项都会在下一次启动自动生效，
 * 无需改动本类。根级文件（{@code AGENTS.md}/{@code MEMORY.md}/{@code SOUL.md}）为稳定且
 * 与 Spring 配置同处根目录，故保留一份极短显式清单，以免误拷框架配置文件。
 *
 * <p>行为由两个配置项控制：
 * <ul>
 *   <li><b>sai.agent.init.enabled（默认 true）</b>：总开关。false 时整体跳过初始化，
 *       不检查、不同步任何文件。</li>
 *   <li><b>sai.agent.init.force（默认 false）</b>：覆盖策略。
 *       <ul>
 *         <li>false：仅<b>补全</b>工作区中缺失的文件/目录，已存在保持不动
 *             （运行期沉淀的长期记忆 {@code MEMORY.md} / {@code memory/} 不会被覆盖）。</li>
 *         <li>true：<b>强制清空并覆盖</b>受管目录（{@code subagents/}、{@code skills/}、
 *             {@code knowledge/}）后全量同步模板文件，并对根文件强制覆盖，
 *             用于把工作区整体复位到打包模板状态。</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>模板源为 classpath（构建后 {@code sai-agent/src/main/resources} 的等价内容），
 * 通过 {@link ResourcePatternResolver} 读取，兼容 IDE 直跑（exploded）与
 * Spring Boot 可执行 JAR 两种部署形态。
 *
 * @DATE: 2026/8/21
 * @AUTHOR: XSL
 */
@Slf4j
@Component
public class AgentInit {

    /** 受管目录：自动扫描其内容；force=true 时先整体清空、再全量同步（复位到打包模板） */
    private static final List<String> MANAGED_DIRS = List.of("subagents", "skills", "knowledge");

    /**
     * 根级文件清单：与工作区根同级的稳定文件，显式列出以避免误拷 Spring 配置
     * （如 application-agent.yml / logback 等）。这些文件名极少变化，不纳入自动扫描。
     */
    private static final List<String> ROOT_FILES = List.of("AGENTS.md", "MEMORY.md", "SOUL.md");

    @Value("${agentScope.workspace:.agentscope/workspace}")
    private String workspace;

    /** 总开关：false 时整体跳过初始化（不检查、不同步任何文件） */
    @Value("${sai.agent.init.enabled:true}")
    private boolean enabled;

    /** 强制清空覆盖开关：true=清空受管目录后全量覆盖；false=仅补全缺失 */
    @Value("${sai.agent.init.force:false}")
    private boolean force;

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * 执行工作区初始化（幂等：多次调用仅首次真正执行）。
     * 由 {@code AgentAIConfig.harnessAgent} 在构建主 Agent 之前调用，确保工作区文件就位。
     */
    public void init() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }
        if (!enabled) {
            log.info("[AgentInit] 初始化已禁用（sai.agent.init.enabled=false），跳过工作区检查与同步");
            return;
        }
        Path ws = resolveWorkspace();
        log.info("[AgentInit] 启动检查工作区：{}（enabled={}, force={}）", ws.toAbsolutePath(), enabled, force);
        try {
            Files.createDirectories(ws);

            if (force) {
                log.warn("[AgentInit] force=true：将清空并覆盖受管目录 {} 后全量同步模板文件"
                                + "（会清除工作区内自行演进的子智能体/技能/知识，以及根 MEMORY.md 等）",
                        MANAGED_DIRS);
                for (String dir : MANAGED_DIRS) {
                    deleteRecursively(ws.resolve(dir));
                }
            }

            int copied = 0;
            int skipped = 0;

            // —— 根级文件：显式短清单，仅补全 / 强制覆盖 ——
            for (String rel : ROOT_FILES) {
                Path target = ws.resolve(rel);
                if (force || !Files.exists(target)) {
                    if (copyFromClasspath(rel, target)) {
                        copied++;
                    }
                } else {
                    skipped++;
                }
            }

            // —— 受管目录：classpath 自动扫描，无需维护清单 ——
            ResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver(getClass().getClassLoader());
            for (String dir : MANAGED_DIRS) {
                Resource[] resources = resolver.getResources("classpath*:" + dir + "/**");
                for (Resource r : resources) {
                    String url = r.getURL().toString();
                    if (url.endsWith("/")) {
                        continue; // 目录项，跳过
                    }
                    // 从 URL 中提取相对于 dir 的路径段（兼容 exploded 与 JAR 两种形态）
                    int idx = url.lastIndexOf("/" + dir + "/");
                    if (idx < 0) {
                        continue;
                    }
                    String rel = dir + url.substring(idx + dir.length() + 1);
                    Path target = ws.resolve(rel);
                    if (force || !Files.exists(target)) {
                        if (copyFromResource(r, rel, target)) {
                            copied++;
                        }
                    } else {
                        skipped++;
                    }
                }
            }
            log.info("[AgentInit] 工作区就绪：本次新增/覆盖 {} 个文件，跳过已存在 {} 个", copied, skipped);
        } catch (Exception e) {
            log.error("[AgentInit] 工作区初始化失败：{}", e.getMessage(), e);
        }
    }

    /** 解析工作区绝对路径：相对路径基于 user.dir 解析 */
    private Path resolveWorkspace() {
        Path p = Paths.get(workspace);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir")).resolve(p).normalize();
        }
        return p;
    }

    /** 从 classpath 读取根文件（按相对路径）并写入目标；缺失则返回 false（不中断整体流程） */
    private boolean copyFromClasspath(String rel, Path target) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(rel)) {
            if (in == null) {
                log.warn("[AgentInit] 模板资源缺失（未打包进 classpath，跳过）：{}", rel);
                return false;
            }
            return writeResource(in, rel, target);
        } catch (IOException e) {
            log.warn("[AgentInit] 复制失败：{} -> {} : {}", rel, target, e.getMessage());
            return false;
        }
    }

    /** 从 Spring Resource 读取并写入目标路径 */
    private boolean copyFromResource(Resource r, String rel, Path target) {
        try (InputStream in = r.getInputStream()) {
            return writeResource(in, rel, target);
        } catch (IOException e) {
            log.warn("[AgentInit] 复制失败：{} -> {} : {}", rel, target, e.getMessage());
            return false;
        }
    }

    private boolean writeResource(InputStream in, String rel, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(target)) {
            Files.deleteIfExists(target);
        }
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /** 递归删除目录（含内容）；不存在则安全跳过 */
    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @NotNull
            @Override
            public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @NotNull
            @Override
            public FileVisitResult postVisitDirectory(@NotNull Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
