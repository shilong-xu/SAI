package xsl.sai.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量内置工具集合 —— 把「时间 / 计算」等无副作用的小工具合并到同一 Bean。
 *
 * <ul>
 *   <li>时间类：{@link #getCurrentTime}、{@link #dateDiff}、{@link #nowPlusDays}、{@link #formatTimestamp}</li>
 *   <li>计算类：{@link #calculate}（受限安全表达式求值）</li>
 * </ul>
 *
 * <p>这些工具不依赖外部服务、无 IO，作为框架未内置的「必要」能力统一声明式注册。
 *
 * @DATE: 2026/7/14
 * @AUTHOR: XSL
 */
@Slf4j
@Component
public class UtilityTool {

    // ============================ 计算：calculate ============================

    private static final Set<String> FUNCS = Set.of(
            "sqrt", "abs", "ceil", "floor", "round", "min", "max",
            "log", "ln", "exp", "sin", "cos", "tan");
    private static final Set<String> CONSTS = Set.of("pi", "e");
    private static final Map<String, Double> CONST_VAL = Map.of("pi", Math.PI, "e", Math.E);

    @Tool(name = "calculate", description = "安全计算数学表达式并返回数值结果。"
            + "支持 + - * / % ^、括号、一元负号，以及函数 sqrt/abs/ceil/floor/round/"
            + "min(a,b)/max(a,b)/log/ln/exp/sin/cos/tan 和常量 pi、e。"
            + "仅做纯数学求值，不执行任何代码，安全无副作用。")
    public String calculate(
            RuntimeContext runtimeContext,
            @ToolParam(name = "expression", required = true,
                    description = "数学表达式，例如 (1+2)*3^2 或 max(sqrt(16), abs(-5))") String expression) {
        if (expression == null || expression.isBlank()) {
            return "表达式为空。";
        }
        try {
            double result = evaluate(expression);
            if (Double.isNaN(result)) return "计算结果非数值（NaN）。";
            if (Double.isInfinite(result)) return "计算结果超出可表示范围（无穷大）。";
            // 整数结果去尾零
            if (result == Math.rint(result) && !Double.isInfinite(result)) {
                return String.valueOf((long) result);
            }
            return String.valueOf(Math.round(result * 1e10) / 1e10);
        } catch (Exception e) {
            return "计算失败：" + e.getMessage();
        }
    }

    // ===== 受限求值器（Shunting-yard + 双栈）=====

    private double evaluate(String expr) {
        // 1) 中缀 → 后缀（RPN）
        Deque<String> out = new ArrayDeque<>();
        Deque<String> ops = new ArrayDeque<>();
        char[] cs = expr.replace(" ", "").toCharArray();
        int i = 0, n = cs.length;
        while (i < n) {
            char c = cs[i];
            if (Character.isDigit(c) || c == '.') {
                StringBuilder num = new StringBuilder();
                while (i < n && (Character.isDigit(cs[i]) || cs[i] == '.')) num.append(cs[i++]);
                out.push(num.toString());
                continue;
            }
            if (Character.isLetter(c)) {
                StringBuilder name = new StringBuilder();
                while (i < n && Character.isLetter(cs[i])) name.append(cs[i++]);
                String id = name.toString().toLowerCase();
                if (CONSTS.contains(id)) {
                    out.push(String.valueOf(CONST_VAL.get(id)));
                } else if (FUNCS.contains(id)) {
                    ops.push(id);
                } else {
                    throw new IllegalArgumentException("未知标识符：" + id);
                }
                continue;
            }
            if (c == ',') { // 函数多参数分隔，直接弹到左括号
                while (!ops.isEmpty() && !ops.peek().equals("(")) out.push(ops.pop());
                i++;
                continue;
            }
            if (c == '(') {
                ops.push("(");
                i++;
                continue;
            }
            if (c == ')') {
                while (!ops.isEmpty() && !ops.peek().equals("(")) out.push(ops.pop());
                if (ops.isEmpty()) throw new IllegalArgumentException("括号不匹配");
                ops.pop(); // 弹出 '('
                if (!ops.isEmpty() && FUNCS.contains(ops.peek())) out.push(ops.pop());
                i++;
                continue;
            }
            // 运算符（含一元负号）
            String op = String.valueOf(c);
            if (c == '-' && (i == 0 || isOperatorOrLParen(cs, i - 1))) {
                // 一元负号：转为特殊标记
                ops.push("u-");
                i++;
                continue;
            }
            while (!ops.isEmpty() && !ops.peek().equals("(")
                    && prec(ops.peek()) >= prec(op)) {
                out.push(ops.pop());
            }
            ops.push(op);
            i++;
        }
        while (!ops.isEmpty()) {
            String o = ops.pop();
            if (o.equals("(")) throw new IllegalArgumentException("括号不匹配");
            out.push(o);
        }
        // 2) 后缀求值
        Deque<Double> st = new ArrayDeque<>();
        for (String t : out) { // out 是栈，逆序遍历
            st.push(Double.parseDouble(t));
        }
        // 用显式队列按 RPN 顺序求值
        List<String> rpn = new java.util.ArrayList<>(out);
        java.util.Collections.reverse(rpn);
        Deque<Double> eval = new ArrayDeque<>();
        for (String t : rpn) {
            if (isNumber(t)) {
                eval.push(Double.parseDouble(t));
            } else if (t.equals("u-")) {
                eval.push(-eval.pop());
            } else if (t.equals("min") || t.equals("max")) {
                double b = eval.pop(), a = eval.pop();
                eval.push(t.equals("min") ? Math.min(a, b) : Math.max(a, b));
            } else if (FUNCS.contains(t)) {
                eval.push(applyFunc(t, eval.pop()));
            } else {
                double b = eval.pop(), a = eval.pop();
                eval.push(applyOp(t, a, b));
            }
        }
        return eval.pop();
    }

    private boolean isOperatorOrLParen(char[] cs, int idx) {
        char p = cs[idx];
        return p == '(' || p == '+' || p == '-' || p == '*' || p == '/' || p == '%' || p == '^';
    }

    private boolean isNumber(String s) {
        try { Double.parseDouble(s); return true; }
        catch (NumberFormatException e) { return false; }
    }

    private int prec(String op) {
        return switch (op) {
            case "u-" -> 4;
            case "^" -> 3;
            case "*", "/", "%" -> 2;
            case "+", "-" -> 1;
            default -> 0;
        };
    }

    private double applyOp(String op, double a, double b) {
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> a / b;
            case "%" -> a % b;
            case "^" -> Math.pow(a, b);
            default -> throw new IllegalArgumentException("未知运算符：" + op);
        };
    }

    private double applyFunc(String f, double x) {
        return switch (f) {
            case "sqrt" -> Math.sqrt(x);
            case "abs" -> Math.abs(x);
            case "ceil" -> Math.ceil(x);
            case "floor" -> Math.floor(x);
            case "round" -> Math.round(x);
            case "log", "ln" -> Math.log(x);
            case "exp" -> Math.exp(x);
            case "sin" -> Math.sin(x);
            case "cos" -> Math.cos(x);
            case "tan" -> Math.tan(x);
            default -> throw new IllegalArgumentException("未知函数：" + f);
        };
    }

    // ============================ 时间：get_current_time / date_diff / now_plus_days / format_timestamp ============================

    @Tool(name = "get_current_time", description = "获取当前日期与时间。"
            + "可指定时区（如 Asia/Shanghai、UTC）与输出格式；不指定则返回本地时区 ISO 格式。")
    public String getCurrentTime(
            RuntimeContext runtimeContext,
            @ToolParam(name = "timezone", required = false,
                    description = "时区 ID，如 Asia/Shanghai / America/New_York / UTC，默认系统默认时区") String timezone,
            @ToolParam(name = "format", required = false,
                    description = "输出格式（java.time 格式，如 yyyy-MM-dd HH:mm:ss），默认 ISO 格式") String format) {
        try {
            ZoneId zone = (timezone == null || timezone.isBlank())
                    ? ZoneId.systemDefault() : ZoneId.of(timezone);
            ZonedDateTime now = ZonedDateTime.now(zone);
            if (format != null && !format.isBlank()) {
                return now.format(DateTimeFormatter.ofPattern(format));
            }
            return now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            return "时区或格式无效：" + e.getMessage();
        } catch (Exception e) {
            log.warn("get_current_time 失败: {}", e.getMessage());
            return "时间查询失败：" + e.getMessage();
        }
    }

    @Tool(name = "date_diff", description = "计算两个日期之间的天数差（date2 - date1）。"
            + "日期格式 yyyy-MM-dd。用于规划、倒计时等场景。")
    public String dateDiff(
            RuntimeContext runtimeContext,
            @ToolParam(name = "date1", required = true, description = "起始日期 yyyy-MM-dd") String date1,
            @ToolParam(name = "date2", required = true, description = "结束日期 yyyy-MM-dd") String date2) {
        try {
            LocalDate d1 = LocalDate.parse(date1.trim());
            LocalDate d2 = LocalDate.parse(date2.trim());
            long days = ChronoUnit.DAYS.between(d1, d2);
            return String.format("%s 到 %s 相差 %d 天", date1, date2, days);
        } catch (DateTimeParseException e) {
            return "日期格式应为 yyyy-MM-dd：" + e.getMessage();
        }
    }

    @Tool(name = "now_plus_days", description = "返回相对今天偏移若干天后的日期（yyyy-MM-dd）。"
            + "days 可为负数表示过去。")
    public String nowPlusDays(
            RuntimeContext runtimeContext,
            @ToolParam(name = "days", required = true, description = "偏移天数，整数") int days) {
        return LocalDate.now().plusDays(days).toString();
    }

    @Tool(name = "format_timestamp", description = "将 Unix 秒级时间戳转换为可读日期时间（默认本地时区）。")
    public String formatTimestamp(
            RuntimeContext runtimeContext,
            @ToolParam(name = "timestamp", required = true, description = "Unix 秒级时间戳") long timestamp,
            @ToolParam(name = "timezone", required = false, description = "时区 ID，默认系统默认") String timezone) {
        try {
            ZoneId zone = (timezone == null || timezone.isBlank())
                    ? ZoneId.systemDefault() : ZoneId.of(timezone);
            LocalDateTime dt = LocalDateTime.ofEpochSecond(timestamp, 0,
                    zone.getRules().getOffset(LocalDateTime.now()));
            return dt.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            return "时间戳转换失败：" + e.getMessage();
        }
    }
}
