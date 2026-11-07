package com.likelion.commute_study_api.study;

import java.time.OffsetDateTime;
import java.util.List;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.subject.SubjectRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class StudyReadService {
    private final CurrentUser currentUser;
    private final SubjectRepository subjects;
    private final StudyReadRepository repository;
    private final CategoryRecommendation recommendation;
    public StudyReadService(CurrentUser currentUser,SubjectRepository subjects,StudyReadRepository repository,
                            CategoryRecommendation recommendation) {
        this.currentUser=currentUser; this.subjects=subjects; this.repository=repository; this.recommendation=recommendation;
    }

    public CategoryList categories(long subjectId,Jwt jwt) {
        var userId=currentUser.authenticatedId(jwt);
        subjects.requireOwned(subjectId,userId);
        var items=repository.categories(subjectId,userId).stream().map(row -> {
            double progress=row.total()==0 ? 0 : (double)row.solved()/row.total();
            Double accuracy=row.graded()==0 ? null : (double)row.correct()/row.graded();
            State state="PREPARING".equals(row.questionStatus()) ? State.PREPARING
                    : progress==0 ? State.NOT_STARTED : progress>=1 ? State.COMPLETED : State.IN_PROGRESS;
            return new CategoryItem(row.id(),row.name(),row.pageStart(),row.pageEnd(),progress,accuracy,
                    row.lastStudiedAt(),state,row.questionStatus());
        }).toList();
        return new CategoryList(items,recommendation.select(items));
    }

    public StudyReadRepository.Explanation explanation(long id,Jwt jwt) {
        return repository.explanation(id,currentUser.authenticatedId(jwt));
    }

    public enum State { PREPARING, NOT_STARTED, IN_PROGRESS, COMPLETED }
    public record CategoryList(List<CategoryItem> categories,Long recommendedId) {}
    public record CategoryItem(long id,String name,int pageStart,int pageEnd,double progress,Double accuracy,
                               OffsetDateTime lastStudiedAt,State state,String questionStatus) {}
}
