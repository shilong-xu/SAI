package xsl.sai.framework.config;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import xsl.sai.framework.base.BaseEntity;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

/**
 * 实体保存审计回调：在 R2DBC 转换前自动填充时间字段。
 * <p>规则：
 * <ul>
 *   <li>新建（{@link Persistable#isNew()} 为 true）：补齐 {@code createTime}，并刷新 {@code updateTime}；</li>
 *   <li>更新（isNew 为 false）：始终刷新 {@code updateTime} 为当前时间。</li>
 * </ul>
 * 这样业务代码无需在每处手动 {@code setUpdateTime}。
 *
 * @author SAI
 */
@Component
public class EntityAuditCallback implements BeforeConvertCallback<BaseEntity> {

    private static final Logger log = LoggerFactory.getLogger(EntityAuditCallback.class);

    @Override
    @NonNull
    public Publisher<BaseEntity> onBeforeConvert(@NonNull BaseEntity entity, @NonNull SqlIdentifier table) {
        LocalDateTime now = LocalDateTime.now();
        boolean isNew = isNewEntity(entity);
        if (isNew && entity.getCreateTime() == null) {
            entity.setCreateTime(now);
        }
        // 无论新建还是更新，都刷新更新时间
        entity.setUpdateTime(now);
        return Mono.just(entity);
    }

    /**
     * 判断实体是否为「新记录」，需区分两种实体：
     * <ul>
     *   <li>实现 {@link Persistable} 的实体（如 ClientMessageEntity / ClientConversationEntity）：
     *       以 {@link Persistable#isNew()} 为准；</li>
     *   <li>未实现 Persistable 的实体（自增主键、保存前 id 为 null）：
     *       沿用 Spring Data R2DBC 的默认规则——{@code @Id} 字段为 null 即新记录。</li>
     * </ul>
     * 若不处理后者，非 Persistable 实体在 {@code save()} 时 {@code isNew} 恒为 false，
     * 导致新建记录的 {@code createTime} 永远不会被本回调填充（入库后 create_time 为空）。
     */
    private boolean isNewEntity(BaseEntity entity) {
        if (entity instanceof Persistable) {
            return ((Persistable<?>) entity).isNew();
        }
        return isIdNull(entity);
    }

    /** 通过反射查找实体（含父类）上 {@link Id} 注解字段，为 null 则视为新记录 */
    private boolean isIdNull(BaseEntity entity) {
        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.isAnnotationPresent(Id.class)) {
                    try {
                        f.setAccessible(true);
                        return f.get(entity) == null;
                    } catch (IllegalAccessException e) {
                        log.warn("[EntityAudit] 读取 @Id 失败, class={}", entity.getClass().getSimpleName(), e);
                        return false;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        // 找不到 @Id：保守当作「已存在」，仅刷 updateTime，不补 createTime
        return false;
    }
}
