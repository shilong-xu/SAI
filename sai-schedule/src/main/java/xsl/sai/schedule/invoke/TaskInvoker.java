package xsl.sai.schedule.invoke;

import cn.hutool.core.text.StrSplitter;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务调用目标执行器
 *
 * <p>解析 {@code invokeTarget} 字符串（形如 {@code beanName.methodName(arg1, arg2)}），
 * 从 Spring 容器中取出对应 bean，反射调用其方法。
 *
 * <p>被调度方法允许返回响应式类型（{@link Mono}/{@link Flux}/{@link CompletionStage}）；
 * 本执行器会在返回前将其汇聚为具体值（见 {@link #unwrapReactive(Object)}）。该汇聚发生在
 * 独立的后台调度线程（{@code ScheduleJob.INVOKE_EXECUTOR}，非响应式事件循环）上，
 * 因此不会阻塞在线的响应式/流式链路——它只用于适配 Quartz {@code Job.execute} 的同步契约。
 *
 * <p>安全约束：仅允许调用 {@code sai.schedule.allowed-beans} 白名单内 bean 的 public 方法，
 * 避免 schedule_task 表被写入后演变为任意方法调用（RCE 风险）。
 *
 * <p><b>注意：白名单必须用 {@link ConfigurationProperties} 绑定，不能用
 * {@code @Value("${sai.schedule.allowed-beans:}") Set<String>}。</b>
 * 后者无法解析 YAML 列表（{@code allowed-beans:} 下的 {@code - xxx} 是序列而非标量），
 * 会静默回落到空集合，使 {@code !allowedBeans.isEmpty()} 判断失效，
 * 白名单校验被整体跳过（等于不限制任何 bean）。
 *
 * @author SAI
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "sai.schedule")
public class TaskInvoker implements ApplicationContextAware {

    private ApplicationContext applicationContext;

    /** 反射调用白名单；为空表示不限制（仅本地调试用，不推荐生产） */
    private Set<String> allowedBeans = new LinkedHashSet<>();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /** 供 {@code @ConfigurationProperties} 绑定（sai.schedule.allowed-beans），保留 YAML 列表写法 */
    public void setAllowedBeans(Set<String> allowedBeans) {
        this.allowedBeans = allowedBeans == null ? new LinkedHashSet<>() : allowedBeans;
    }

    /**
     * 启动期回显生效的白名单。为空时明确告警——避免「以为配了、实际没生效」这种静默失效。
     */
    @PostConstruct
    void logAllowedBeans() {
        if (allowedBeans.isEmpty()) {
            log.warn("[调度] sai.schedule.allowed-beans 为空：反射调用白名单未生效，任何 bean 都可被调度表调用（仅限本地调试）");
        } else {
            log.info("[调度] 反射调用白名单已生效 --> {}", allowedBeans);
        }
    }

    /**
     * 执行调用目标
     *
     * @param invokeTarget 形如 {@code beanName.methodName(...)}
     * @return 方法返回值（转为字符串记录），无返回值时返回 null
     * @throws Exception 解析或调用失败时抛出（由上层统一记录日志）
     */
    public Object invoke(String invokeTarget) throws Exception {
        if (StrUtil.isBlank(invokeTarget)) {
            throw new IllegalArgumentException("invokeTarget 不能为空");
        }

        List<String> parts = StrSplitter.split(invokeTarget, '.', 2, true, true);
        if (parts.size() != 2) {
            throw new IllegalArgumentException(
                    "invokeTarget 格式应为 beanName.methodName(...)，实际：" + invokeTarget);
        }

        String beanName = parts.get(0).trim();
        String methodSegment = parts.get(1).trim();

        int parenIdx = methodSegment.indexOf('(');
        if (parenIdx < 0) {
            throw new IllegalArgumentException("invokeTarget 缺少方法括号：" + invokeTarget);
        }
        String methodName = methodSegment.substring(0, parenIdx).trim();
        String argsStr = methodSegment.substring(parenIdx + 1, methodSegment.lastIndexOf(')'));

        // 白名单校验
        if (!allowedBeans.isEmpty() && !allowedBeans.contains(beanName)) {
            throw new SecurityException("bean 不在调度白名单内，禁止调用 --> " + beanName);
        }

        Object bean = applicationContext.getBean(beanName);

        // 先按方法名预查找，判断是否存在「单 String 参数」目标方法。
        // 若存在，则把括号内原始字符串整体作为该参数透传，避免逗号/引号歧义
        // （例如 agentChat('# 多行含逗号换行字符串') 应作为一个参数而非被切分）。
        Method singleStringMethod = findSingleStringMethod(bean, methodName);
        List<Object> args;
        if (singleStringMethod != null) {
            args = new ArrayList<>();
            args.add(stripQuotes(argsStr.trim()));
        } else {
            args = parseArgs(argsStr);
        }

        Class<?>[] paramTypes = args.stream().map(Object::getClass).toArray(Class<?>[]::new);

        Method method;
        try {
            method = bean.getClass().getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            // 退化为按方法名 + 参数数量查找（适用于基本类型签名场景）
            method = findByArity(bean, methodName, args.size());
        }

        if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
            throw new SecurityException("仅允许调用 public 方法 --> " + beanName + "." + methodName);
        }

        log.info("[调度] 调用目标 --> {}#{}", beanName, methodName);
        return unwrapReactive(method.invoke(bean, args.toArray()));
    }

    /**
     * 解包被调方法的响应式返回值，使其归一为可供日志落库的具体对象。
     *
     * <p>本方法由 {@code ScheduleJob} 通过 {@code INVOKE_EXECUTOR}（独立后台线程池，
     * <strong>非</strong>响应式事件循环）反射调用，因此此处 {@code .block()}/{.join()} 只会阻塞
     * 后台调度线程，不会阻塞在线的流式接口；这是适配 Quartz 同步 {@code Job.execute} 契约所必需的桥接。
     *
     * @param result 反射调用原始返回值
     * @return 解包后的具体值；非响应式类型原样返回
     */
    private Object unwrapReactive(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof CompletionStage<?> stage) {
            return stage.toCompletableFuture().join();
        }
        if (result instanceof Mono<?> mono) {
            return mono.block();
        }
        if (result instanceof Flux<?> flux) {
            return flux.collectList().block();
        }
        return result;
    }

    /**
     * 查找名为 {@code methodName} 且「仅有一个 String 参数」的 public 方法。
     * 用于单字符串透传场景；找不到则返回 null（走常规多参解析）。
     */
    private Method findSingleStringMethod(Object bean, String methodName) {
        for (Method m : bean.getClass().getMethods()) {
            if (m.getName().equals(methodName)
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == String.class) {
                return m;
            }
        }
        return null;
    }

    /**
     * 去除参数字面量最外层成对引号（单/双），用于单字符串透传时还原真实内容。
     * 若外层无成对引号则原样返回。
     */
    private String stripQuotes(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    /**
     * 按方法名 + 参数个数查找（忽略精确类型，主要用于无参或单参任务）
     */
    private Method findByArity(Object bean, String methodName, int arity) throws NoSuchMethodException {
        List<String> signatures = new ArrayList<>();
        for (Method m : bean.getClass().getMethods()) {
            if (m.getName().equals(methodName)) {
                signatures.add(methodName + "(" + m.getParameterCount() + " args)");
                if (m.getParameterCount() == arity) {
                    return m;
                }
            }
        }
        String hint = signatures.isEmpty()
                ? "（该 bean 上不存在任何名为 " + methodName + " 的 public 方法）"
                : "（可用签名：" + String.join("、", signatures) + "）";
        throw new NoSuchMethodException(methodName + "(arity=" + arity + ") " + hint);
    }

    /**
     * 解析参数字面量：支持字符串（单/双引号，引号内逗号不拆分）、数字、布尔，逗号分隔
     */
    private List<Object> parseArgs(String argsStr) {
        List<Object> args = new ArrayList<>();
        if (StrUtil.isBlank(argsStr)) {
            return args;
        }
        // 按引号感知的方式切分，避免把字符串内的逗号误拆
        for (String raw : splitRespectingQuotes(argsStr)) {
            String token = raw.trim();
            if (StrUtil.isBlank(token)) {
                continue;
            }
            if ((token.startsWith("'") && token.endsWith("'"))
                    || (token.startsWith("\"") && token.endsWith("\""))) {
                args.add(token.substring(1, token.length() - 1));
            } else if ("true".equalsIgnoreCase(token) || "false".equalsIgnoreCase(token)) {
                args.add(Boolean.parseBoolean(token));
            } else if (token.contains(".")) {
                try {
                    args.add(Double.parseDouble(token));
                } catch (NumberFormatException e) {
                    args.add(token);
                }
            } else {
                try {
                    args.add(Long.parseLong(token));
                } catch (NumberFormatException e) {
                    args.add(token);
                }
            }
        }
        return args;
    }

    /**
     * 引号感知的逗号切分（支持带引号的参数内包含逗号）
     */
    private List<String> splitRespectingQuotes(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                sb.append(c);
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
                sb.append(c);
            } else if (c == ',') {
                result.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) result.add(sb.toString());
        return result;
    }
}
