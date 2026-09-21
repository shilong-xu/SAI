package xsl.sai.framework.config;

import cn.hutool.json.JSONNull;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;

/**
 * JSON 相关配置
 * &#064;DATE: 2025/5/10
 * &#064;AUTHOR: XSL
 *
 */
@JsonComponent
public class JsonSerializerConfig extends JsonSerializer<JSONNull> {

    /**
     * JSONUtil 处理 空字符的情况
     * @param jsonNull
     * @param jsonGenerator
     * @param serializerProvider
     * @throws IOException
     */
    @Override
    public void serialize(JSONNull jsonNull, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        jsonGenerator.writeNull();
    }

}
