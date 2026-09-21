package xsl.sai.framework.client;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileAppender;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xsl.sai.framework.context.HttpRequestContext;

import java.io.File;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class LogClient {

    @Autowired
    private Environment environment;

    /** LogClient 文件日志总开关，默认开启；关闭后所有请求/响应日志不再写入文件 */
    @Value("${sai.log.client.enabled:true}")
    private boolean enabled;

    private String systemPath;
    private final Lock lock = new ReentrantLock();
    private final AtomicReference<FileAppender> fileAppenderRef = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> localDateTimeRef = new AtomicReference<>();

    @PostConstruct
    public void init() {
        systemPath = environment.getProperty("log.path");
        if (StrUtil.isEmpty(systemPath)) {
            systemPath = System.getProperty("user.dir") + File.separator + "log";
        }
    }

    public Mono<Void> log(Object body) {
        if (!enabled || Objects.isNull(body)) {
            return Mono.empty();
        }

        return Mono.defer(() -> {
            try {
                String traceId = getTraceId();
                String logStr;

                if (body instanceof String) {
                    logStr = (String) body;
                } else if (body instanceof Exception exception) {
                    logStr = exception.getMessage() + " " + Arrays.toString(exception.getStackTrace());
                } else {
                    logStr = JSONUtil.toJsonStr(body);
                }

                String finalLogStr = logStr;
                ensureFileAppender();
                FileAppender appender = fileAppenderRef.get();
                return Mono.fromRunnable(() -> {
                    lock.lock();
                    try {
                        appender.append(DateUtil.now() + " [" + traceId + "] " + finalLogStr);
                        appender.flush();
                    } finally {
                        lock.unlock();
                    }
                }).subscribeOn(Schedulers.boundedElastic()).then();
            } catch (Exception e) {
                return Mono.empty();
            }
        }).onErrorResume(e -> Mono.empty());
    }

    private String getTraceId() {
        HttpRequestContext.RequestInfo requestInfo = HttpRequestContext.get();
        if (requestInfo != null && StrUtil.isNotBlank(requestInfo.getTraceId())) {
            return requestInfo.getTraceId();
        }
        return "NO_TRACE_ID";
    }

    private void ensureFileAppender() {
        LocalDateTime now = LocalDate.now().atStartOfDay();
        LocalDateTime current = localDateTimeRef.get();

        if (current == null || Duration.between(current, now).toDays() >= 1) {
            lock.lock();
            try {
                current = localDateTimeRef.get();
                if (current == null || Duration.between(current, now).toDays() >= 1) {
                    localDateTimeRef.set(now);
                    String newLogFile = systemPath
                            + File.separator + now.getYear()
                            + "_" + now.getMonthValue()
                            + File.separator + now.getDayOfMonth()
                            + ".txt";
                    log.info("[Log] 日志文件记录位置 --> {}", newLogFile);
                    fileAppenderRef.set(new FileAppender(FileUtil.touch(newLogFile), 1, true));
                }
            } finally {
                lock.unlock();
            }
        }
    }

}