package com.sky.decisioncompanion.service;

import cn.dev33.satoken.stp.StpUtil;
import com.sky.decisioncompanion.common.BusinessException;
import com.sky.decisioncompanion.common.ResultCode;
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
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }

        try {
            User user = userService.createUser(normalizedUsername, passwordEncoder.encode(password));
            return loginUser(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }
    }

    public AuthResponse login(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        User user = userService.getUserByUsername(normalizedUsername);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ResultCode.AUTH_FAILED);
        }

        return loginUser(user);
    }

    public AuthUser currentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userService.getUserById(userId);
        if (user == null) {
            StpUtil.logout();
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
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
            throw new BusinessException(ResultCode.USERNAME_EMPTY);
        }
        if (normalized.length() > 50) {
            throw new BusinessException(ResultCode.USERNAME_TOO_LONG);
        }
        if (UNSUPPORTED_USERNAME_CHARS.matcher(normalized).find()) {
            throw new BusinessException(ResultCode.USERNAME_UNSUPPORTED_CHARS);
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ResultCode.PASSWORD_TOO_SHORT);
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(ResultCode.PASSWORD_TOO_LONG);
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
