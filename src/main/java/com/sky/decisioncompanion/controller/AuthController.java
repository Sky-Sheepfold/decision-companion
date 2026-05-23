package com.sky.decisioncompanion.controller;

import com.sky.decisioncompanion.common.Result;
import com.sky.decisioncompanion.common.ResultCode;
import com.sky.decisioncompanion.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "账号认证", description = "用户名密码注册、登录和当前用户")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "注册账号", description = "使用用户名和密码注册，用户名支持中文")
    public ResponseEntity<Result<AuthService.AuthResponse>> register(@Valid @RequestBody AuthRequest request) {
        return ResponseEntity.ok(Result.success(ResultCode.REGISTER_SUCCESS, authService.register(request.username(), request.password())));
    }

    @PostMapping("/login")
    @Operation(summary = "登录账号", description = "使用用户名和密码登录，返回 Sa-Token JWT")
    public ResponseEntity<Result<AuthService.AuthResponse>> login(@Valid @RequestBody AuthRequest request) {
        return ResponseEntity.ok(Result.success(ResultCode.LOGIN_SUCCESS, authService.login(request.username(), request.password())));
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户", description = "根据 Authorization Bearer Token 返回当前登录用户")
    public ResponseEntity<Result<AuthService.AuthUser>> me() {
        return ResponseEntity.ok(Result.success(authService.currentUser()));
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录", description = "退出当前 Sa-Token 登录态")
    public ResponseEntity<Result<Void>> logout() {
        authService.logout();
        return ResponseEntity.ok(Result.success(ResultCode.LOGOUT_SUCCESS, null));
    }

    public record AuthRequest(
            @Parameter(description = "用户名，支持中文") @NotBlank @Size(max = 50) String username,
            @Parameter(description = "密码，8-72 位") @NotBlank @Size(min = 8, max = 72) String password) {}
}
