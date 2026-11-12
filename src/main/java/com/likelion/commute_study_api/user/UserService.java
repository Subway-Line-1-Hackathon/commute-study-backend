package com.likelion.commute_study_api.user;

import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.config.StudyProperties;
import com.likelion.commute_study_api.common.study.StudyMode;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final StudyProperties studyProperties;

    public UserService(CurrentUser currentUser, UserRepository userRepository, StudyProperties studyProperties) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.studyProperties = studyProperties;
    }

    @Transactional
    public UserResponse getMe(Jwt jwt) {
        var user = userRepository.findById(currentUser.ensureUser(jwt));
        UserResponse.Settings settings = user.oneWayMin() == null ? null : new UserResponse.Settings(
                user.oneWayMin(), user.fromStation(), user.toStation(), user.oneWayMin(),
                StudyMode.of(user.oneWayMin(), studyProperties.getCurriculumMinMinutes()));
        boolean active = user.nickname() != null && !user.nickname().isBlank()
                && settings != null;
        return new UserResponse(user.userId(), user.nickname(),
                active ? UserResponse.Status.ACTIVE : UserResponse.Status.AUTHENTICATED,
                user.hasSubject(), settings);
    }

    @Transactional(readOnly = true)
    public LegacyAuth legacyAuth(Jwt jwt) {
        var userId = currentUser.authenticatedId(jwt);
        String nickname = userRepository.findNickname(userId);
        return new LegacyAuth(userId, jwt.getClaimAsString("email"), nickname, nickname != null && !nickname.isBlank());
    }

    @Transactional(readOnly = true)
    public LegacyNickname legacyNickname(Jwt jwt) {
        var userId = currentUser.authenticatedId(jwt);
        String nickname = userRepository.findNickname(userId);
        return new LegacyNickname(userId, nickname, nickname != null && !nickname.isBlank());
    }

    @Transactional
    public SavedNickname saveNickname(Jwt jwt, NicknameRequest request) {
        var userId = currentUser.authenticatedId(jwt);
        String nickname = NicknamePolicy.normalize(request.nickname());
        if (!NicknamePolicy.valid(nickname)) {
            throw new ApiException(CommonErrorCode.VALIDATION_ERROR, "닉네임은 2~10자로 입력해주세요.");
        }
        userRepository.saveNickname(userId, nickname);
        return new SavedNickname(userId, nickname, "닉네임 저장 성공");
    }

    public record LegacyAuth(UUID userId, String email, String nickname, boolean nicknameSet) {}
    public record LegacyNickname(UUID userId, String nickname, boolean nicknameSet) {}
    public record SavedNickname(UUID userId, String nickname, String message) {}
}
