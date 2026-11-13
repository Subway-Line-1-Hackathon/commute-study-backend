package com.likelion.commute_study_api.report;

import java.time.*;
import java.util.*;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.config.ReportProperties;
import com.likelion.commute_study_api.subject.SubjectRepository;
import com.likelion.commute_study_api.feedback.FeedbackStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class ReportService {
    private final CurrentUser currentUser;
    private final SubjectRepository subjects;
    private final ReportRepository repository;
    private final ReportProperties properties;
    public ReportService(CurrentUser currentUser,SubjectRepository subjects,ReportRepository repository,ReportProperties properties) {
        this.currentUser=currentUser; this.subjects=subjects; this.repository=repository; this.properties=properties;
    }
    public SessionReport session(long subjectId,long sessionId,Jwt jwt) {
        var user=currentUser.authenticatedId(jwt); subjects.requireOwned(subjectId,user);
        var info=repository.session(subjectId,sessionId,user);
        var stats=repository.sessionStats(subjectId,sessionId,user);
        return new SessionReport(new Today(minutes(info.startedAt(),stats),stats.solved(),ratio(stats.correct(),stats.graded()),stats.wrong()),info.feedback());
    }
    public SessionResult session(long sessionId,Jwt jwt) {
        var user=currentUser.authenticatedId(jwt);
        var info=repository.ownedSession(sessionId,user);
        var stats=repository.sessionStats(sessionId,user);
        return new SessionResult(sessionId,new SessionToday(stats.solved(),stats.correct(),ratio(stats.correct(),stats.graded()),
                minutes(info.startedAt(),stats),stats.solved(),stats.wrong()),info.feedback(),info.feedbackStatus());
    }
    public SubjectReport all(long subjectId,Jwt jwt) {
        var user=currentUser.authenticatedId(jwt); subjects.requireOwned(subjectId,user);
        var trend=repository.trend(subjectId,user,properties.getMinAnswers()).stream()
                .map(d->new Trend(d.date(),ratio(d.correct(),d.graded()),d.answers())).toList();
        var stats=repository.categories(subjectId,user);
        var categories=stats.stream().map(c->new Category(c.id(),ratio(c.correct(),c.graded())))
                .sorted(Comparator.comparing(Category::accuracy,Comparator.nullsLast(Comparator.naturalOrder())).thenComparingLong(Category::id))
                .toList();
        long graded=stats.stream().mapToLong(ReportRepository.CategoryStats::graded).sum();
        long correct=stats.stream().mapToLong(ReportRepository.CategoryStats::correct).sum();
        return new SubjectReport(trend,categories,ratio(correct,graded));
    }
    private static Double ratio(long correct,long graded) { return graded==0?null:(double)correct/graded; }
    private static long minutes(OffsetDateTime startedAt,ReportRepository.SessionStats stats) {
        return startedAt==null || stats.lastAnswer()==null ? 0
                : Math.max(0,Duration.between(startedAt.toInstant(),stats.lastAnswer().toInstant()).toMinutes());
    }
    public record SessionResult(long sessionId,SessionToday today,
            @Schema(nullable=true,description="생성된 AI 피드백. READY 이외에는 null") String feedback,
            @Schema(description="PENDING일 때만 폴링. READY: 생성 성공, SKIPPED: 생성 생략, FAILED: 생성 또는 제출 실패")
            FeedbackStatus feedbackStatus) {}
    public record SessionToday(
            @Schema(description="저장된 답안 수. 채점 대기 포함") long answered,
            @Schema(description="정답 수. 자가 판정 포함") long correct,
            @Schema(nullable=true,description="correct / 채점 완료 답안 수. 채점 완료가 없으면 null") Double accuracy,
            @Schema(description="기존 today와 동일: 시작부터 마지막 답안까지의 분(내림), 최소 0") long minutes,
            @Schema(description="기존 today와 동일: answered의 별칭") long solved,
            @Schema(description="기존 today와 동일: 채점 완료 오답 수") long wrong) {}
    public sealed interface Report permits SessionReport,SubjectReport {}
    public record SessionReport(Today today,String feedback) implements Report {}
    public record Today(long minutes,long solved,Double accuracy,long wrong) {}
    public record SubjectReport(List<Trend> accuracyTrend,List<Category> byCategory,Double average) implements Report {}
    public record Trend(LocalDate date,Double accuracy,long answers) {}
    public record Category(long id,Double accuracy) {}
}
