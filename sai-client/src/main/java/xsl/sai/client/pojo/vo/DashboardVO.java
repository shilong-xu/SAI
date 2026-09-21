package xsl.sai.client.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 协作空间首页仪表盘 VO
 *
 * @author SAI
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardVO {

    /** 当前使用的模型名（agentScope.models.modelName） */
    private String model;

    /** 今日 Token 消耗 */
    private Long todayTokens;

    /** 累计 Token 消耗 */
    private Long totalTokens;

    /** 知识库条目总数 */
    private Long knowledgeTotal;

    /** 知识库已向量化切块数 */
    private Long knowledgeChunks;

    /** 今日调度执行次数 */
    private Long scheduleToday;

    /** 累计调度执行次数 */
    private Long scheduleTotal;

    /** 累计调度执行成功次数 */
    private Long scheduleSuccess;

    /** 累计收件数（direction = 0） */
    private Long emailInbox;

    /** 累计发件数（direction = 1） */
    private Long emailSent;

    /** 未摘要收件数（direction = 0 且 summary 为 NULL；status 列已移除） */
    private Long emailUnsummarized;

    /** 今日邮件数（收 + 发） */
    private Long emailToday;

    /** 待办条目总数 */
    private Long todoTotal;

    /** 待办未完成数 */
    private Long todoActive;

    /** 今日新增待办数 */
    private Long todoToday;

    /** 最新 2 条知识库记录预览 */
    private List<PreviewItem> knowledgeRecent;

    /** 最新 2 条调度日志预览 */
    private List<PreviewItem> scheduleRecent;

    /** 最新 2 条邮件预览 */
    private List<PreviewItem> emailRecent;

    /** 最新 2 条待办预览 */
    private List<PreviewItem> todoRecent;

    /** 近 7 日趋势（含今日，无数据的日期补 0） */
    private List<DailyItem> series;

    /** 卡片预览项（通用结构，适配各模块的「最新 2 条」展示） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreviewItem {
        /** 主标题（知识库名/任务名/邮件主题） */
        private String title;
        /** 副标题（备注/执行结果摘要/发件人） */
        private String subtitle;
        /** 辅助信息（更新时间/耗时+成功状态/处理状态） */
        private String extra;
        /** 时间（yyyy-MM-dd 或 MM-dd HH:mm） */
        private String time;
    }

    /** 单日趋势项 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyItem {
        /** 日期（yyyy-MM-dd） */
        private String date;
        /** 当日 Token 消耗 */
        private Long tokens;
        /** 当日邮件数（收 + 发） */
        private Long emails;
        /** 当日调度执行次数 */
        private Long schedules;
        /** 当日新增待办数 */
        private Long todos;
    }
}
