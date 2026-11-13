package com.likelion.commute_study_api.subject;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubjectService {
    private final CurrentUser currentUser;
    private final SubjectRepository subjects;
    private final Clock clock;

    public SubjectService(CurrentUser currentUser, SubjectRepository subjects,
                          @Qualifier("subjectClock") Clock clock) {
        this.currentUser = currentUser;
        this.subjects = subjects;
        this.clock = clock.withZone(ZoneId.of("Asia/Seoul"));
    }

    @Transactional
    public CreatedSubject create(Jwt jwt, SubjectRequest request) {
        long id = subjects.create(currentUser.ensureUser(jwt), request.name(), request.examDate());
        return new CreatedSubject(id);
    }

    @Transactional
    public SubjectList list(Jwt jwt) {
        var userId = currentUser.ensureUser(jwt);
        LocalDate today = LocalDate.now(clock);
        return new SubjectList(subjects.findAllByUserId(userId).stream().map(subject -> {
            LocalDate examDate = subject.examDate();
            Long dDay = examDate == null || examDate.isBefore(today)
                    ? null : ChronoUnit.DAYS.between(today, examDate);
            return new SubjectItem(subject.id(), subject.name(), examDate, dDay);
        }).toList());
    }

    @Transactional
    public UpdatedSubject update(Jwt jwt, long subjectId, SubjectPatchRequest request) {
        var userId = currentUser.authenticatedId(jwt);
        var subject = subjects.findOwnedForUpdate(subjectId, userId)
                .orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND));
        String name = request.nameProvided() ? request.name() : subject.name();
        LocalDate examDate = request.examDateProvided() ? request.examDate() : subject.examDate();
        if (request.nameProvided() || request.examDateProvided()) {
            subjects.updateOwned(subjectId, userId, name, examDate);
        }
        LocalDate today = LocalDate.now(clock);
        Long dDay = examDate == null || examDate.isBefore(today) ? null : ChronoUnit.DAYS.between(today, examDate);
        return new UpdatedSubject(subjectId, name, examDate, dDay);
    }

    public record UpdatedSubject(long id, String name, LocalDate examDate, Long dDay) {}
    public record CreatedSubject(long subjectId) {}
    public record SubjectList(List<SubjectItem> subjects) {}
    public record SubjectItem(long id, String name,
                              @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate examDate,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Long dDay) {}
}

