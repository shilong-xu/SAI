package xsl.sai.framework.util;

import java.util.Locale;

/**
 * 向量 / 文本 通用工具方法
 *
 * <p>集中收敛各 Tool 与 Service 中重复的私有辅助方法（向量 JSON 拼接、空串处理、
 * 数值转换等），避免同份逻辑多处分发、精度不一。
 *
 * @author SAI
 */
public final class VectorUtils {

    private VectorUtils() {
    }

    /**
     * 将 float[] 格式化为向量 JSON 字面量（如 {@code [0.1,0.2,0.3]}），仅含数字，可安全内联进 SQL。
     * 统一使用 6 位小数，与 {@code R2dbcClient#toVectorJson} 保持一致。
     */
    public static String toVecJson(float[] vec) {
        if (vec == null || vec.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.6f", vec[i]));
        }
        return sb.append("]").toString();
    }

    /** 空白（null 或全空白）返回 null，否则 trim */
    public static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 转 DB 绑定值：null → 空字符串，规避 R2DBC 无法绑定 null 参数导致的 "Value must not be null" */
    public static String val(String s) {
        return s == null ? "" : s;
    }

    /** 把可能为 Number / 数字字符串的对象安全转为 Long（失败返回 null） */
    public static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
