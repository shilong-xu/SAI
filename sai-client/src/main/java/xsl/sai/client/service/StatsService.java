package xsl.sai.client.service;

import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.vo.DashboardVO;

/**
 * 仪表盘统计 服务
 *
 * @author SAI
 */
public interface StatsService {

    /** 首页仪表盘：模型信息 / Token 消耗 / 学习记录统计 + 近 7 日趋势 */
    Mono<DashboardVO> dashboard();
}
