package xsl.sai.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Shell（xshell）命令执行工具（声明式 {@link Tool}）。
 *
 * <p><b>安全约束：每次真正执行前必须获得用户的明确授权（human-in-the-loop）。</b></p>
 *
 * <p>实现方式：工具默认<b>不执行</b>命令，仅返回「待确认」预览；只有当调用方在对话中
 * 取得用户许可、并以 {@code confirm=true} 再次调用本工具时，命令才会真正执行。
 * 这一机制确保任何 shell 命令落地前都经过用户确认，避免误操作。
 * 此外，若配置了命令白名单（allowed-commands），真正执行前还会做可执行文件名校验。</p>
 *
 * <p>Agent 使用约定：想执行命令时，应先把拟执行的命令告知用户并等待其许可，
 * 不要未经授权就自行传入 confirm=true。</p>
 *
 * @DATE: 2026/7/20
 * @AUTHOR: XSL
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sai.agent.shell.enabled", havingValue = "true", matchIfMissing = true)
public class ShellTool {

    /** 允许执行的可执行文件白名单；为空表示不限制（仅靠用户确认兜底） */
    private final Set<String> allowedCommands;

    public ShellTool(
            @Value("${sai.agent.shell.allowed-commands:ls,cat,pwd,echo,head,tail,grep,find,python3,date}")
            String allowedCommandsCsv) {
        this.allowedCommands = (allowedCommandsCsv == null || allowedCommandsCsv.isBlank())
                ? Set.of()
                : Arrays.stream(allowedCommandsCsv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
//        log.info("Shell 工具已启用（每次执行需用户确认），白名单命令: {}", allowedCommands);
    }

    @Tool(name = "execute_shell_command",
            description = "执行 shell(xshell) 命令。"
                    + "⚠️ 安全约束：每次真正执行前必须获得用户的明确授权。"
                    + "默认不会执行命令——调用时仅返回「待确认」预览；"
                    + "只有当调用方在对话中获得用户许可、并以 confirm=true 再次调用本工具时，命令才会真正执行。"
                    + "Agent 应先将拟执行命令告知用户并等待其许可，不要未经授权自行传入 confirm=true。")
    public String executeCommand(
            RuntimeContext runtimeContext,
            @ToolParam(name = "command", required = true,
                    description = "要执行的 shell 命令") String command,
            @ToolParam(name = "confirm", required = false,
                    description = "是否已获得用户明确授权执行。必须为 true 才会真正执行；否则仅返回待确认预览") Boolean confirm) {
        if (command == null || command.isBlank()) {
            return "命令为空。";
        }
        // ---- 未授权：仅返回预览，绝不执行 ----
        if (confirm == null || !confirm) {
            return "⏸️ 待确认命令（尚未执行）：\n"
                    + command + "\n"
                    + "该命令未被执行。请务必先取得用户明确授权；"
                    + "获得许可后，再次调用本工具并传入 confirm=true 以执行。";
        }
        // ---- 已授权：若配置了白名单则校验可执行文件名 ----
        if (!allowedCommands.isEmpty()) {
            String first = command.trim().split("\\s+")[0];
            if (!allowedCommands.contains(first)) {
                return "⛔ 命令不在白名单内（" + first + "），已拒绝执行。允许: " + allowedCommands;
            }
        }
        return run(command);
    }

    /** 真正执行命令并捕获输出（带超时与截断） */
    private String run(String command) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (out.length() > 8000) {
                        out.append("\n...[输出已截断]");
                        break;
                    }
                    out.append(line).append("\n");
                }
            }
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                return "命令执行超时（>30s）已被终止。";
            }
            int code = p.exitValue();
            return "命令已执行（exit=" + code + "）：\n" + out;
        } catch (Exception e) {
            log.warn("shell 执行失败: {}", e.getMessage());
            return "命令执行失败：" + e.getMessage();
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }
}
