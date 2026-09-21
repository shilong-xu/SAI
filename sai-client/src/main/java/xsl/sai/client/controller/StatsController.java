package xsl.sai.client.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import xsl.sai.client.service.StatsService;
import xsl.sai.framework.base.BaseController;
import xsl.sai.framework.result.Result;

/**
 * 仪表盘统计接口（协作空间首页，需登录）
 *
 * @author SAI
 */
@Slf4j
@RestController
@RequestMapping("/api/stats")
public class StatsController extends BaseController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 首页仪表盘：模型 / Token 消耗 / 学习记录统计 + 近 7 日趋势 */
    @GetMapping("/dashboard")
    public Mono<Result> dashboard() {
        return statsService.dashboard()
                .map(Result::success)
                .onErrorResume(e -> {
                    log.error("仪表盘统计查询失败", e);
                    return Mono.just(Result.error(500, "统计查询失败：" + e.getMessage()));
                });
    }
}
