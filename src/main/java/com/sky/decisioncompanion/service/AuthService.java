package com.sky.decisioncompanion.service;

import cn.dev33.satoken.stp.StpUtil;
import com.sky.decisioncompanion.model.User;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 72;
    private static final Pattern UNSUPPORTED_USERNAME_CHARS = Pattern.compile("[\\s/\\\\]");

    private final UserService userService;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserService userService, BCryptPasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthResponse register(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);

        if (userService.getUserByUsername(normalizedUsername) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }

        try {
            User user = userService.createUser(normalizedUsername, passwordEncoder.encode(password));
            return loginUser(user);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("用户名已存在");
        }
    }

    public AuthResponse login(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        User user = userService.getUserByUsername(normalizedUsername);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        return loginUser(user);
    }

    public AuthUser currentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userService.getUserById(userId);
        if (user == null) {
            StpUtil.logout();
            throw new IllegalArgumentException("用户不存在");
        }
        return AuthUser.from(user);
    }

    public void logout() {
        StpUtil.logout();
    }

    private AuthResponse loginUser(User user) {
        StpUtil.login(user.getId());
        return new AuthResponse(StpUtil.getTokenValue(), AuthUser.from(user));
    }

    private String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (normalized.length() > 50) {
            throw new IllegalArgumentException("用户名不能超过 50 个字符");
        }
        if (UNSUPPORTED_USERNAME_CHARS.matcher(normalized).find()) {
            throw new IllegalArgumentException("用户名不能包含空白字符、斜杠或反斜杠");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少需要 8 位");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码不能超过 72 位");
        }
    }

    public record AuthResponse(String token, AuthUser user) {}

    public record AuthUser(Long id, String username, Boolean onboarded, java.time.LocalDateTime createdAt) {
        private static AuthUser from(User user) {
            return new AuthUser(
                    user.getId(),
                    user.getUsername(),
                    user.getOnboarded(),
                    user.getCreatedAt());
        }
    }
}
