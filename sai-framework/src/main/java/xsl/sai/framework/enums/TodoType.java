package xsl.sai.framework.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 待办事项类型（对应 {@code todo_item.type}，<b>落库为 tinyint 数字 code</b>）。
 *
 * <p>放在 framework 模块是为了让 client（页面 / 接口）与 agent（{@code TodoTool}、
 * 邮件转待办流水线）共用同一份「code ↔ 中文标签」映射，避免两处各写一套。
 *
 * <p>取值表：{@code 1=任务 2=事项 3=会议 4=回信}。
 *
 * @author SAI
 */
@Getter
@AllArgsConstructor
public enum TodoType {

    /** 要产出一个交付物（写文档、修 bug、出报告、上线、提交数据） */
    TASK(1, "任务"),

    /** 需要跟进 / 知悉 / 留痕，但没有明确交付物（通知、续费提醒、审批结果） */
    MATTER(2, "事项"),

    /** 有具体时间 + 参与方的会（评审、例会、对齐） */
    MEETING(3, "会议"),

    /** 需要给某人回一封邮件 / 回复某个问题 */
    REPLY(4, "回信"),
    ;

    /** 落库 code（与 {@code todo_item.type} 一致） */
    private final int code;

    /** 中文标签（页面展示 / Agent 输出用） */
    private final String label;

    /** 默认类型：事项（最轻量、最不容易误导） */
    public static final TodoType DEFAULT = MATTER;

    /** code → 枚举；null 或越界返回 null */
    public static TodoType of(Integer code) {
        if (code == null) {
            return null;
        }
        for (TodoType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }

    /** code → 中文标签；null 或越界返回 null */
    public static String labelOf(Integer code) {
        TodoType t = of(code);
        return t == null ? null : t.label;
    }

    /** 归一：合法 code 原样返回；null / 越界一律返回 null（落库为「未指定」） */
    public static Integer normalize(Integer code) {
        return of(code) == null ? null : code;
    }

    /**
     * 文本 → code：兼容<b>数字</b>（{@code "3"}）与<b>中文标签</b>（{@code "会议"}）两种写法；
     * 无法识别返回 null。Agent 工具入参是模型生成的文本，两种写法都能接住更稳。
     */
    public static Integer parse(Object text) {
        if (text == null) {
            return null;
        }
        String s = text.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return normalize(Integer.valueOf(s));
        } catch (NumberFormatException ignore) {
            // 非数字：按中文标签匹配（忽略大小写与首尾空白）
        }
        for (TodoType t : values()) {
            if (t.label.equalsIgnoreCase(s)) {
                return t.code;
            }
        }
        return null;
    }

    /** 取值说明（用于提示词补充与错误提示），形如 {@code 1任务 / 2事项 / 3会议 / 4回信} */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (TodoType t : values()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(t.code).append(t.label);
        }
        return sb.toString();
    }
}
