package com.likelion.commute_study_api.common.auth;

import java.util.UUID;

import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.user.UserRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CurrentUser {

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Spring Security가 검증한 JWT를 전달한다. 반환 전에 app_user 행의 존재를 보장한다.
     * INSERT를 수행하므로 호출하는 서비스의 트랜잭션도 readOnly=false여야 한다.
     */
    @Transactional
    public UUID ensureUser(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ApiException(CommonErrorCode.UNAUTHORIZED);
        }
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new ApiException(CommonErrorCode.UNAUTHORIZED);
        }
        userRepository.createIfAbsent(userId);
        return userId;
    }
}
