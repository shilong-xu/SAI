package xsl.sai.framework.base;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import xsl.sai.framework.result.Result;

/**
 * &#064;DATE: 2026/6/15 19:31
 * &#064;AUTHOR: XSL
 *
 */
@Slf4j
public class BaseController {

    public Flux<Result> fluxNone() {
        return Flux.just(Result.error(401, "请求不能为空"));
    }

}
