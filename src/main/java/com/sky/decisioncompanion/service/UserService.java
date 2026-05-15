package com.sky.decisioncompanion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.model.User;
import com.sky.decisioncompanion.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getOrCreateUser(String sessionId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getSessionId, sessionId);
        User user = userRepository.selectOne(wrapper);

        if (user == null) {
            user = new User(sessionId);
            userRepository.insert(user);
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
}
