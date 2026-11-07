package com.likelion.commute_study_api.user;

import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.config.StudyProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.common.study.StudyMode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
    private final CurrentUser currentUser;
    private final UserRepository users;
    private final SettingsRepository settings;
    private final StudyProperties studyProperties;

    public SettingsService(CurrentUser currentUser, UserRepository users,
                           SettingsRepository settings, StudyProperties studyProperties) {
        this.currentUser = currentUser;
        this.users = users;
        this.settings = settings;
        this.studyProperties = studyProperties;
    }

    @Transactional
    public SavedSettings save(Jwt jwt, SettingsRequest request) {
        settings.save(currentUser.ensureUser(jwt), request);
        return new SavedSettings(request.nickname(), request.oneWayMin(), mode(request.oneWayMin()));
    }

    @Transactional
    public Settings get(Jwt jwt) {
        var user = users.findById(currentUser.ensureUser(jwt));
        if (user.oneWayMin() == null) throw new ApiException(CommonErrorCode.NOT_FOUND);
        return new Settings(user.nickname(), user.oneWayMin(), user.fromStation(), user.toStation(),
                user.oneWayMin(), mode(user.oneWayMin()));
    }

    private StudyMode mode(int minutes) {
        return StudyMode.of(minutes, studyProperties.getCurriculumMinMinutes());
    }

    public record SavedSettings(String nickname, int studyMin, StudyMode mode) {}
    public record Settings(String nickname, int oneWayMin, String fromStation, String toStation,
                           int studyMin, StudyMode mode) {}
}
