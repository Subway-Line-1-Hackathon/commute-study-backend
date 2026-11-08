package com.likelion.commute_study_api.tutor;

import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.exception.ApiException;

@Service
public class TutorQuota {
    private final JdbcTemplate jdbc;
    private final AiProperties properties;
    private final Clock clock;
    public TutorQuota(JdbcTemplate jdbc,AiProperties properties,@Qualifier("tutorClock") Clock clock) {
        this.jdbc=jdbc; this.properties=properties; this.clock=clock;
    }
    public LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    public Usage get(UUID user) {
        var counts=jdbc.queryForList("SELECT count FROM public.ai_question_usage WHERE user_id=? AND date=?",Integer.class,user,today());
        int used=counts.isEmpty()?0:counts.getFirst(), limit=properties.getQuestionDailyLimit();
        return new Usage(used,limit,Math.max(0,limit-used));
    }
    public void requireAvailable(UUID user) {
        if(get(user).remaining()==0) throw new ApiException(TutorErrorCode.AI_QUESTION_LIMIT);
    }
    /** Transactional upsert in two statements: no lost-update read/modify/write in Java. */
    @Transactional
    public LocalDate charge(UUID user) {
        LocalDate day=today();
        jdbc.update("INSERT INTO public.ai_question_usage(user_id,date,count) VALUES (?,?,0) ON CONFLICT DO NOTHING",user,day);
        if(jdbc.update("UPDATE public.ai_question_usage SET count=count+1 WHERE user_id=? AND date=? AND count<?",
                user,day,properties.getQuestionDailyLimit())!=1) throw new ApiException(TutorErrorCode.AI_QUESTION_LIMIT);
        return day;
    }
    /** Compensates a known downstream write failure after a committed charge; use the charged date. */
    @Transactional
    public void refund(UUID user,LocalDate day) {
        jdbc.update("UPDATE public.ai_question_usage SET count=count-1 WHERE user_id=? AND date=? AND count>0",user,day);
    }
    public record Usage(int used,int limit,int remaining) {}
}
