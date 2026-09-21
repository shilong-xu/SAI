package xsl.sai.schedule.config;

import org.quartz.spi.JobFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Quartz 定时调度模块配置
 *
 * <p>交由 Spring Boot 自动配置 {@link org.springframework.scheduling.quartz.SchedulerFactoryBean}，
 * 本配置仅负责让 Quartz 在创建 Job 实例时支持 {@code @Autowired} 注入 Spring Bean。</p>
 *
 * @author SAI
 */
@Configuration
public class ScheduleConfig {

    /**
     * 让 Quartz 在创建 Job 实例时执行 Spring 依赖注入
     */
    @Bean
    public JobFactory springBeanJobFactory(ApplicationContext applicationContext) {
        org.springframework.scheduling.quartz.SpringBeanJobFactory factory =
                new org.springframework.scheduling.quartz.SpringBeanJobFactory();
        factory.setApplicationContext(applicationContext);
        return factory;
    }
}
