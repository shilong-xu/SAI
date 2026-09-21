package xsl.sai.framework.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * &#064;DATE: 2026/5/12 18:50
 * &#064;AUTHOR: XSL
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Result {

    private Integer code;
    private String message;
    private Object data;


    public static Result success(Object data) {
        Result result = new Result();
        result.setData(data);
        result.setCode(200);
        result.setMessage("success");
        return result;
    }

    public static Result error(String message) {
        Result result = new Result();
        result.setMessage(message);
        result.setCode(500);
        return result;
    }

    public static Result error(Integer code, String message) {
        Result result = new Result();
        result.setMessage(message);
        result.setCode(code);
        return result;
    }

}
