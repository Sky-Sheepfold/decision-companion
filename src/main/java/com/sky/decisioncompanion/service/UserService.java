package com.sky.decisioncompanion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sky.decisioncompanion.common.BusinessException;
import com.sky.decisioncompanion.common.ResultCode;
import com.sky.decisioncompanion.model.User;
import com.sky.decisioncompanion.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getUserByUsername(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        return userRepository.selectOne(wrapper);
    }

    public User getUserById(Long id) {
        return userRepository.selectById(id);
    }

    public User createUser(String username, String encodedPassword) {
        User user = new User(username, encodedPassword);
        userRepository.insert(user);
        return getUserByUsername(username);
    }

    public boolean isOnboarded(Long userId) {
        User user = getUserById(userId);
        return user != null && Boolean.TRUE.equals(user.getOnboarded());
    }

    public void markOnboarded(Long userId) {
        User user = getUserById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        user.setOnboarded(true);
        userRepository.updateById(user);
    }
}
