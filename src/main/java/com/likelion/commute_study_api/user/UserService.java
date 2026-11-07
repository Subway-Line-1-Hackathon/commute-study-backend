package com.likelion.commute_study_api.user;

import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.config.StudyProperties;
import com.likelion.commute_study_api.common.study.StudyMode;
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
                && settings != null && user.hasSubject();
        return new UserResponse(user.userId(), user.nickname(),
                active ? UserResponse.Status.ACTIVE : UserResponse.Status.AUTHENTICATED,
                user.hasSubject(), settings);
    }
}
