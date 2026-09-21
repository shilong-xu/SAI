package xsl.sai.client.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import xsl.sai.client.service.EmailReceiveService;
import xsl.sai.framework.base.BaseController;
import xsl.sai.framework.result.Result;

import java.util.Map;

/**
 * 邮件接口（需登录）—— 收发统一记录
 *
 * <p>接收邮件由定时任务单向入库、发送邮件由 {@code send_mail} 成功后自动入库，两者都无需人工录入，
 * 故不提供新增 / 修改，仅提供查询（可按 {@code direction} 区分收发）、软删除与手动抓取。
 *
 * @author SAI
 */
@Slf4j
@RestController
@RequestMapping("/api/email")
public class ReceivedEmailController extends BaseController {

    private final EmailReceiveService emailReceiveService;

    public ReceivedEmailController(EmailReceiveService emailReceiveService) {
        this.emailReceiveService = emailReceiveService;
    }

    /**
     * 列表（分页 + 关键词模糊：主题 / 正文 / 发件人）
     *
     * @param direction 邮件方向 0-接收 1-发送（不传表示收发都返回）
     */
    @GetMapping("/list")
    public Mono<Result> list(@RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Integer direction,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "50") int size) {
        return emailReceiveService.list(keyword, direction, page, size).collectList()
                .zipWith(emailReceiveService.count(keyword, direction).defaultIfEmpty(0L))
                .map(t -> Result.success(Map.of("list", t.getT1(), "total", t.getT2())))
                .onErrorResume(e -> Mono.just(Result.error(500, e.getMessage())));
    }

    /** 详情 */
    @GetMapping("/{id}")
    public Mono<Result> detail(@PathVariable("id") Long id) {
        return emailReceiveService.getById(id)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(500, e.getMessage())));
    }

    /** 删除（逻辑删除） */
    @DeleteMapping("/{id}")
    public Mono<Result> delete(@PathVariable("id") Long id) {
        return emailReceiveService.delete(id)
                .then(Mono.just(Result.success("ok")))
                .onErrorResume(e -> Mono.just(Result.error(500, e.getMessage())));
    }

    /** 手动触发一次抓取（便于调试，绕过定时调度） */
    @PostMapping("/fetch")
    public Mono<Result> fetch() {
        return emailReceiveService.fetchAndStore()
                .map(c -> Result.success(Map.of("fetched", c)))
                .onErrorResume(e -> Mono.just(Result.error(500, e.getMessage())));
    }
}
