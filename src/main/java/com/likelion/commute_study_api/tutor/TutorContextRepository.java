package com.likelion.commute_study_api.tutor;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.commute_study_api.ai.support.AiPage;
import com.likelion.commute_study_api.ai.tutor.TutorAskRequest;
import com.likelion.commute_study_api.common.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TutorContextRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper=new ObjectMapper();
    public TutorContextRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public TutorAskRequest request(long id,UUID user,AskRequest input) {
        var rows=jdbc.query("""
                SELECT q.stem,q.choices,q.answer,q.accepted_answers,q.explanation,c.id AS category_id,c.name,
                    c.material_id,c.page_start,c.page_end
                FROM public.question q JOIN public.category c ON c.id=q.category_id
                JOIN public.subject s ON s.id=c.subject_id WHERE q.id=? AND s.user_id=?
                """,(rs,n)->new Context(rs.getLong("category_id"),rs.getString("name"),rs.getLong("material_id"),
                rs.getInt("page_start"),rs.getInt("page_end"),rs.getString("stem"),strings(rs.getString("choices")),
                rs.getString("answer"),strings(rs.getString("accepted_answers")),rs.getString("explanation")),id,user);
        var c=rows.stream().findFirst().orElseThrow(()->new ApiException(CommonErrorCode.NOT_FOUND));
        var pages=jdbc.query("SELECT page,text FROM public.material_page WHERE material_id=? AND page BETWEEN ? AND ? ORDER BY page",
                (rs,n)->new AiPage(rs.getInt("page"),rs.getString("text")),c.material(),c.start(),c.end());
        var answers=jdbc.queryForList("""
                SELECT a.answer FROM public.attempt a JOIN public.session s ON s.id=a.session_id
                WHERE a.user_id=? AND s.user_id=? AND a.question_id=?
                ORDER BY CASE WHEN s.status='IN_PROGRESS' THEN 0 ELSE 1 END,a.answered_at DESC,a.id DESC LIMIT 1
                """,String.class,user,user,id);
        var history=input.history()==null?List.<AskRequest.Message>of():input.history();
        var recent=history.subList(Math.max(0,history.size()-6),history.size()).stream()
                .map(m->new TutorAskRequest.HistoryItem(m.role(),m.content())).toList();
        String answer=c.answer();
        if(answer==null || answer.isBlank()) answer=c.accepted().isEmpty()?"":c.accepted().getFirst();
        return new TutorAskRequest(new TutorAskRequest.Category(c.id(),c.name(),pages),
                new TutorAskRequest.Question(c.stem(),c.choices(),answer,c.explanation()==null?"":c.explanation(),
                        answers.isEmpty()||answers.getFirst()==null?"":answers.getFirst()),recent,input.message());
    }
    private List<String> strings(String json) {
        if(json==null) return List.of();
        try {
            var node=mapper.readTree(json);
            if(node.isTextual()) node=mapper.readTree(node.textValue()); // H2 JSON representation.
            if(node.isNull()) return List.of();
            if(!node.isArray()) throw new IllegalArgumentException();
            var values=new ArrayList<String>();
            for(var value:node) { if(!value.isTextual()) throw new IllegalArgumentException(); values.add(value.textValue()); }
            return values;
        } catch(Exception e) { throw new ApiException(CommonErrorCode.INTERNAL_ERROR); }
    }
    private record Context(long id,String name,long material,int start,int end,String stem,List<String> choices,
                           String answer,List<String> accepted,String explanation) {}
}
