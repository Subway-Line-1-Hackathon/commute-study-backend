package com.likelion.commute_study_api.subject;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SubjectServiceTest {
    @ParameterizedTest
    @CsvSource({"2026-11-06T14:59:59Z,1", "2026-11-06T15:00:00Z,0", "2026-11-07T14:59:59Z,0"})
    void usesKstMidnightEvenWhenInjectedClockIsUtc(String instant, long expected) {
        var currentUser = mock(CurrentUser.class);
        var repository = mock(SubjectRepository.class);
        var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(UUID.randomUUID().toString()).build();
        UUID userId = UUID.fromString(jwt.getSubject());
        when(currentUser.ensureUser(jwt)).thenReturn(userId);
        when(repository.findAllByUserId(userId)).thenReturn(List.of(
                new SubjectRepository.Subject(1L, "수학", LocalDate.of(2026, 11, 7))));
        var service = new SubjectService(currentUser, repository, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        assertThat(service.list(jwt).subjects().getFirst().dDay()).isEqualTo(expected);
        verify(repository).findAllByUserId(userId);
    }
}
