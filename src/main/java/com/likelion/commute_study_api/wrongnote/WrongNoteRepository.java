package com.likelion.commute_study_api.wrongnote;

import java.time.OffsetDateTime;
import java.util.*;
import com.likelion.commute_study_api.common.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WrongNoteRepository {
    private final JdbcTemplate jdbc;
    public WrongNoteRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public void requireCategory(long subjectId,long categoryId,UUID user) {
        boolean found=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.category c JOIN public.subject s ON s.id=c.subject_id
                    WHERE c.id=? AND s.id=? AND s.user_id=?)
                """,Boolean.class,categoryId,subjectId,user));
        if(!found) throw new ApiException(CommonErrorCode.NOT_FOUND);
    }

    public List<Item> list(long subjectId,UUID user,Long categoryId,int maxOpen,int maxResolved) {
        var args=new ArrayList<Object>(List.of(subjectId,user,user,maxOpen,maxResolved));
        String filter="";
        if(categoryId!=null) { filter=" AND category_id=?"; args.add(categoryId); }
        // Rank across the subject before category filtering, matching SessionRepository.wrongListed.
        return jdbc.query("""
                WITH ranked AS (
                    SELECT wn.question_id,q.category_id,q.stem,wn.wrong_count,wn.status,wn.last_wrong_at,
                        ROW_NUMBER() OVER (PARTITION BY wn.status ORDER BY wn.last_wrong_at DESC,wn.question_id DESC) AS position
                    FROM public.wrong_note wn JOIN public.question q ON q.id=wn.question_id
                    JOIN public.category c ON c.id=q.category_id JOIN public.subject s ON s.id=c.subject_id
                    WHERE s.id=? AND s.user_id=? AND wn.user_id=?
                ) SELECT question_id,category_id,stem,wrong_count,status,last_wrong_at FROM ranked
                  WHERE ((status='OPEN' AND position<=?) OR (status='RESOLVED' AND position<=?))
                """+filter+" ORDER BY last_wrong_at DESC,question_id DESC",
                (rs,n)->new Item(rs.getLong("question_id"),rs.getLong("category_id"),rs.getString("stem"),
                        rs.getInt("wrong_count"),rs.getString("status"),rs.getObject("last_wrong_at",OffsetDateTime.class)),args.toArray());
    }
    public record Item(long questionId,long categoryId,String stem,int wrongCount,String status,OffsetDateTime lastWrongAt) {}
}
