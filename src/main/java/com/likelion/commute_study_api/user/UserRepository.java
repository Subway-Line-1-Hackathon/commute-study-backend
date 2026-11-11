package com.likelion.commute_study_api.user;

import java.sql.SQLException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;

@Repository
public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * app_user.id는 Supabase auth.users를 참조한다. 탈퇴로 auth.users가 지워졌는데 아직 만료되지 않은 토큰으로
     * 요청이 오면 FK 위반이 나므로, 500이 아니라 인증 실패(401)로 돌려준다(#67).
     */
    public void createIfAbsent(UUID userId) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO public.app_user (id, nickname)
                    VALUES (?, NULL)
                    ON CONFLICT DO NOTHING
                    """, userId);
        } catch (DataIntegrityViolationException e) {
            if (FOREIGN_KEY_VIOLATION.equals(sqlState(e))) {
                log.warn("[회원] 인증 계정이 없는 토큰 요청 userId={}", userId);
                throw new ApiException(CommonErrorCode.UNAUTHORIZED);
            }
            throw e;
        }
    }

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    public UserSnapshot findById(UUID userId) {
        return jdbcTemplate.queryForObject("""
                SELECT u.id, u.nickname, c.one_way_min, c.from_station, c.to_station,
                       EXISTS (SELECT 1 FROM public.subject s WHERE s.user_id = u.id) AS has_subject
                FROM public.app_user u
                LEFT JOIN public.commute_profile c ON c.user_id = u.id
                WHERE u.id = ?
                """, (rs, rowNum) -> new UserSnapshot(
                rs.getObject("id", UUID.class), rs.getString("nickname"),
                rs.getObject("one_way_min", Integer.class), rs.getString("from_station"),
                rs.getString("to_station"), rs.getBoolean("has_subject")), userId);
    }

    public String findNickname(UUID userId) {
        return jdbcTemplate.query("SELECT nickname FROM public.app_user WHERE id = ?",
                rs -> rs.next() ? rs.getString("nickname") : null, userId);
    }

    /** Called in the service transaction: atomic create-if-missing plus nickname-only update. */
    public void saveNickname(UUID userId, String nickname) {
        createIfAbsent(userId);
        jdbcTemplate.update("UPDATE public.app_user SET nickname = ? WHERE id = ?", nickname, userId);
    }

    public record UserSnapshot(UUID userId, String nickname, Integer oneWayMin,
                               String fromStation, String toStation, boolean hasSubject) {
    }
}
