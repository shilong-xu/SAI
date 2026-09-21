package xsl.sai.client.controller;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import xsl.sai.client.pojo.dto.LoginDTO;
import xsl.sai.client.pojo.vo.AuthVO;
import xsl.sai.framework.annotation.NoAuth;
import xsl.sai.framework.config.AccountProvider;
import xsl.sai.framework.enums.CodeEnum;
import xsl.sai.framework.result.Result;

import java.util.Optional;

/**
 * 认证接口（个人工具：账户密码登录，无状态 JWT）。
 *
 * @author XSL (适配 SAI 个人工具项目)
 * @date 2026-07-14
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AccountProvider accountProvider;

    /** 登录（免鉴权） */
    @NoAuth
    @PostMapping("/login")
    public Mono<Result> login(@RequestBody LoginDTO dto) {
        if (dto == null || StrUtil.isEmpty(dto.getUsername()) || StrUtil.isEmpty(dto.getPassword())) {
            return Mono.just(Result.error(CodeEnum.PARAM_MISSING.getCode(), CodeEnum.PARAM_MISSING.getMessage()));
        }
        Optional<AccountProvider.AccountInfo> infoOpt = accountProvider.verify(dto.getUsername(), dto.getPassword());
        if (infoOpt.isEmpty()) {
            return Mono.just(Result.error(CodeEnum.LOGIN_FAILED.getCode(), CodeEnum.LOGIN_FAILED.getMessage()));
        }
        AccountProvider.AccountInfo info = infoOpt.get();
        // JWT（胡图 JWTUtil）：生成自包含令牌，载荷含账号与用户姓名，前端存储后随请求携带
        String token = accountProvider.generateToken(info.getUsername(), info.getDisplayName());
        AuthVO vo = AuthVO.builder()
                .token(token)
                .username(info.getUsername())
                .displayName(info.getDisplayName())
                .build();
        return Mono.just(Result.success(vo));
    }

    /** 登出（前端清 token 即可；无状态后端无需真正失效） */
    @NoAuth
    @PostMapping("/logout")
    public Mono<Result> logout() {
        return Mono.just(Result.success("ok"));
    }

    /** 当前登录态探测（需鉴权，由 AuthWebFilter 保证通过即有效） */
    @GetMapping("/me")
    public Mono<Result> me() {
        return Mono.just(Result.success(java.util.Map.of("authenticated", true)));
    }
}
