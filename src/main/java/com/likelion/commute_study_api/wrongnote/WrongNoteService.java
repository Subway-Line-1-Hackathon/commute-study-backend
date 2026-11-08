package com.likelion.commute_study_api.wrongnote;

import java.util.List;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.config.WrongNoteProperties;
import com.likelion.commute_study_api.subject.SubjectRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class WrongNoteService {
    private final CurrentUser currentUser;
    private final SubjectRepository subjects;
    private final WrongNoteRepository repository;
    private final WrongNoteProperties properties;
    public WrongNoteService(CurrentUser currentUser,SubjectRepository subjects,WrongNoteRepository repository,WrongNoteProperties properties) {
        this.currentUser=currentUser; this.subjects=subjects; this.repository=repository; this.properties=properties;
    }
    public Response list(long subjectId,Long categoryId,Jwt jwt) {
        var user=currentUser.authenticatedId(jwt);
        subjects.requireOwned(subjectId,user);
        if(categoryId!=null) repository.requireCategory(subjectId,categoryId,user);
        return new Response(repository.list(subjectId,user,categoryId,properties.getMaxOpen(),properties.getMaxResolved()));
    }
    public record Response(List<WrongNoteRepository.Item> items) {}
}
