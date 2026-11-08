package com.likelion.commute_study_api.report;

import java.time.*;
import java.util.*;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.config.ReportProperties;
import com.likelion.commute_study_api.subject.SubjectRepository;
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
        long minutes=info.startedAt()==null || stats.lastAnswer()==null ? 0
                : Math.max(0,Duration.between(info.startedAt().toInstant(),stats.lastAnswer().toInstant()).toMinutes());
        return new SessionReport(new Today(minutes,stats.solved(),ratio(stats.correct(),stats.graded()),stats.wrong()),info.feedback());
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
    public sealed interface Report permits SessionReport,SubjectReport {}
    public record SessionReport(Today today,String feedback) implements Report {}
    public record Today(long minutes,long solved,Double accuracy,long wrong) {}
    public record SubjectReport(List<Trend> accuracyTrend,List<Category> byCategory,Double average) implements Report {}
    public record Trend(LocalDate date,Double accuracy,long answers) {}
    public record Category(long id,Double accuracy) {}
}
