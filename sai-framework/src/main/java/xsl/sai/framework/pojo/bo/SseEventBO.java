package xsl.sai.framework.pojo.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 事件载体（广播总线上的最小单元）。
 *
 * <p><b>投递规则</b>：{@code userId == null} 表示广播，所有在线连接都能收到；
 * 否则只有该用户的连接会收到（订阅侧按 userId 过滤）。
 *
 * @author SAI
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SseEventBO {

    /** 目标用户（null = 广播） */
    private String userId;

    /** SSE 事件名（前端 addEventListener 监听的名字） */
    private String event;

    /** 事件数据（JSON 字符串） */
    private String data;

    /** 事件时间戳（毫秒） */
    private long time;
}
