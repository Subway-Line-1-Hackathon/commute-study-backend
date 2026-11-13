package com.likelion.commute_study_api.subject;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SubjectServiceTest {
    @Test
    void partialUpdatePreservesOmittedDateAndUsesKst() {
        var currentUser = mock(CurrentUser.class);
        var repository = mock(SubjectRepository.class);
        UUID userId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(userId.toString()).build();
        when(currentUser.authenticatedId(jwt)).thenReturn(userId);
        when(repository.findOwnedForUpdate(3, userId)).thenReturn(Optional.of(
                new SubjectRepository.Subject(3, "기존", LocalDate.of(2026, 11, 7))));
        var service = new SubjectService(currentUser, repository, Clock.fixed(Instant.parse("2026-11-06T15:00:00Z"), ZoneOffset.UTC));
        var result = service.update(jwt, 3, new SubjectPatchRequest(true, "변경", false, null));
        assertThat(result.name()).isEqualTo("변경");
        assertThat(result.examDate()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(result.dDay()).isZero();
        verify(repository).updateOwned(3, userId, "변경", LocalDate.of(2026, 11, 7));
    }

    @Test
    void emptyPatchDoesNotWriteAndMissingSubjectReturns404() {
        var currentUser = mock(CurrentUser.class);
        var repository = mock(SubjectRepository.class);
        UUID userId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(userId.toString()).build();
        when(currentUser.authenticatedId(jwt)).thenReturn(userId);
        when(repository.findOwnedForUpdate(3, userId)).thenReturn(Optional.of(new SubjectRepository.Subject(3, "기존", null)));
        var service = new SubjectService(currentUser, repository, Clock.systemUTC());
        assertThat(service.update(jwt, 3, new SubjectPatchRequest(false, null, false, null)).name()).isEqualTo("기존");
        verify(repository, never()).updateOwned(anyLong(), any(), any(), any());
        assertThatThrownBy(() -> service.update(jwt, 4, new SubjectPatchRequest(true, "변경", false, null)))
                .isInstanceOfSatisfying(com.likelion.commute_study_api.common.exception.ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(com.likelion.commute_study_api.common.exception.CommonErrorCode.NOT_FOUND));
    }

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
