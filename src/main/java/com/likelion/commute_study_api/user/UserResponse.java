package com.likelion.commute_study_api.user;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.likelion.commute_study_api.common.study.StudyMode;

public record UserResponse(UUID userId, String nickname, Status status, boolean hasSubject,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Settings settings) {

    public enum Status {
        AUTHENTICATED, ACTIVE
    }

    public record Settings(int oneWayMin, String fromStation, String toStation,
                           int studyMin, StudyMode mode) {
    }
}
