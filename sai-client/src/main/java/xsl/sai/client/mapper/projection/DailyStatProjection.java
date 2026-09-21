package xsl.sai.client.mapper.projection;

/**
 * 日统计投影（GROUP BY DATE_FORMAT 聚合行的映射接口）
 * <p>供仪表盘按日聚合查询使用：statDate = 'yyyy-MM-dd'，statCount = 当日汇总值。
 *
 * @author SAI
 */
public interface DailyStatProjection {

    /** 统计日期（yyyy-MM-dd） */
    String getStatDate();

    /** 当日汇总值（token 求和 / 记录数） */
    Long getStatCount();
}
