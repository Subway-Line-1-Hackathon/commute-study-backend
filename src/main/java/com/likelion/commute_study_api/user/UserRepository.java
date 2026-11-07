package com.likelion.commute_study_api.user;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createIfAbsent(UUID userId) {
        jdbcTemplate.update("""
                INSERT INTO public.app_user (id, nickname)
                VALUES (?, NULL)
                ON CONFLICT DO NOTHING
                """, userId);
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

    public record UserSnapshot(UUID userId, String nickname, Integer oneWayMin,
                               String fromStation, String toStation, boolean hasSubject) {
    }
}
