package xsl.sai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/**
 * 工程启动类
 * &#064;DATE: 2026/6/8 10:37
 * &#064;AUTHOR: XSL
 *
 */
@SpringBootApplication
public class SpringMain {
    public static void main(String[] args) {
        // 统一使用东八区：避免 LocalDateTime.now() 在 UTC 环境下写入时间慢 8 小时
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(SpringMain.class, args);
    }
}
