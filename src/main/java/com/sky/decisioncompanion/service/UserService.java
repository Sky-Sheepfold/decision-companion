package com.sky.decisioncompanion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.model.User;
import com.sky.decisioncompanion.repository.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getOrCreateUser(String sessionId) {
        User user = getUserBySessionId(sessionId);

        if (user == null) {
            user = new User(sessionId);
            try {
                userRepository.insert(user);
            } catch (DuplicateKeyException e) {
                User existing = getUserBySessionId(sessionId);
                if (existing != null) {
                    return existing;
                }
                throw e;
            }
        }

        if (user.getId() == null) {
            User persisted = getUserBySessionId(sessionId);
            if (persisted == null || persisted.getId() == null) {
                throw new IllegalStateException("用户创建失败，无法获取自增ID: " + sessionId);
            }
            user = persisted;
        }

        return user;
    }

    public User getUserBySessionId(String sessionId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getSessionId, sessionId);
        return userRepository.selectOne(wrapper);
    }

    public boolean isOnboarded(String sessionId) {
        User user = getUserBySessionId(sessionId);
        return user != null && Boolean.TRUE.equals(user.getOnboarded());
    }

    public void updateNickname(String sessionId, String nickname) {
        User user = getOrCreateUser(sessionId);
        user.setNickname(nickname);
        userRepository.updateById(user);
    }

    public void markOnboarded(String sessionId) {
        User user = getOrCreateUser(sessionId);
        user.setOnboarded(true);
        userRepository.updateById(user);
    }
}
