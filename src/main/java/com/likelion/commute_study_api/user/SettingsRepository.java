package com.likelion.commute_study_api.user;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {
    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called after ensureUser, inside the service's write transaction. */
    public void save(UUID userId, SettingsRequest request) {
        // This row lock serializes settings saves for the same user, including first saves.
        jdbc.update("UPDATE public.app_user SET nickname = ? WHERE id = ?", request.nickname(), userId);
        int updated = jdbc.update("""
                UPDATE public.commute_profile
                SET one_way_min = ?, from_station = ?, to_station = ?
                WHERE user_id = ?
                """, request.oneWayMin(), request.fromStation(), request.toStation(), userId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO public.commute_profile (user_id, one_way_min, from_station, to_station)
                    VALUES (?, ?, ?, ?)
                    """, userId, request.oneWayMin(), request.fromStation(), request.toStation());
        }
    }
}
