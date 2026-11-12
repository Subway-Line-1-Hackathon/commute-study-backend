package com.likelion.commute_study_api.wrongnote;

import java.time.OffsetDateTime;
import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.commute_study_api.common.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WrongNoteRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    public WrongNoteRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public void requireCategory(long subjectId,long categoryId,UUID user) {
        boolean found=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.category c JOIN public.subject s ON s.id=c.subject_id
                    WHERE c.id=? AND s.id=? AND s.user_id=?)
                """,Boolean.class,categoryId,subjectId,user));
        if(!found) throw new ApiException(CommonErrorCode.NOT_FOUND);
    }

    public List<Item> list(long subjectId,UUID user,Long categoryId,int maxOpen,int maxResolved) {
        var args=new ArrayList<Object>(List.of(user,subjectId,user,user,maxOpen,maxResolved));
        String filter="";
        if(categoryId!=null) { filter=" AND category_id=?"; args.add(categoryId); }
        // Rank across the subject before category filtering, matching SessionRepository.wrongListed.
        return jdbc.query("""
                WITH ranked AS (
                    SELECT wn.question_id,q.category_id,c.name AS category_name,q.type::text AS type,q.stem,
                        q.choices::text AS choices,q.answer,q.source_page,
                        (SELECT a.answer FROM public.attempt a WHERE a.question_id=wn.question_id AND a.user_id=?
                            AND a.correct=false ORDER BY a.answered_at DESC LIMIT 1) AS last_answer,
                        wn.wrong_count,wn.status,wn.last_wrong_at,
                        ROW_NUMBER() OVER (PARTITION BY wn.status ORDER BY wn.last_wrong_at DESC,wn.question_id DESC) AS position
                    FROM public.wrong_note wn JOIN public.question q ON q.id=wn.question_id
                    JOIN public.category c ON c.id=q.category_id JOIN public.subject s ON s.id=c.subject_id
                    WHERE s.id=? AND s.user_id=? AND wn.user_id=?
                ) SELECT question_id,category_id,category_name,type,stem,choices,answer,source_page,last_answer,
                      wrong_count,status,last_wrong_at FROM ranked
                  WHERE ((status='OPEN' AND position<=?) OR (status='RESOLVED' AND position<=?))
                """+filter+" ORDER BY last_wrong_at DESC,question_id DESC",
                (rs,n)->new Item(rs.getLong("question_id"),rs.getLong("category_id"),rs.getString("category_name"),
                        rs.getString("type"),rs.getString("stem"),choicesOf(rs.getString("type"),rs.getString("choices")),
                        rs.getString("answer"),rs.getObject("source_page",Integer.class),rs.getString("last_answer"),
                        rs.getInt("wrong_count"),rs.getString("status"),rs.getObject("last_wrong_at",OffsetDateTime.class)),
                args.toArray());
    }

    /** H2 테스트 DB는 JSONB 컬럼을 getString()하면 텍스트 노드로 한 번 더 감싸 돌려준다. */
    private List<String> choicesOf(String type,String json) {
        if(!"MC".equals(type)||json==null) return null;
        try {
            var node=objectMapper.readTree(json);
            if(node.isTextual()) node=objectMapper.readTree(node.textValue());
            if(!node.isArray()||node.isEmpty()) return null;
            List<String> values=new ArrayList<>();
            for(var value:node) values.add(value.textValue());
            return values;
        } catch(JsonProcessingException e) {
            throw new IllegalStateException("보기 목록 JSON 읽기 실패",e);
        }
    }

    public record Item(long questionId,long categoryId,String categoryName,String type,String stem,List<String> choices,
                       String answer,Integer sourcePage,String lastAnswer,int wrongCount,String status,OffsetDateTime lastWrongAt) {}
}
