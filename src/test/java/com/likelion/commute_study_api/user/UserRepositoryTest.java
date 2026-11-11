package com.likelion.commute_study_api.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;

/** 탈퇴 후 남은 토큰으로 회원 행을 만들려 할 때의 처리(#67). */
class UserRepositoryTest {

	private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static UserRepository failingWith(RuntimeException error) {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.update(anyString(), any(Object[].class))).thenThrow(error);
		return new UserRepository(jdbc);
	}

	private static DataIntegrityViolationException violation(String sqlState) {
		return new DataIntegrityViolationException("violation", new SQLException("db", sqlState));
	}

	@Test
	void 인증_계정이_지워진_사용자는_401로_바꾼다() {
		// PostgreSQL FK 위반(23503): app_user.id → auth.users(id)
		assertThatThrownBy(() -> failingWith(violation("23503")).createIfAbsent(USER))
				.isInstanceOfSatisfying(ApiException.class,
						e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
								.isEqualTo(CommonErrorCode.UNAUTHORIZED));
	}

	@Test
	void 다른_무결성_위반은_그대로_던진다() {
		DataIntegrityViolationException notNull = violation("23502");
		assertThatThrownBy(() -> failingWith(notNull).createIfAbsent(USER)).isSameAs(notNull);

		DataIntegrityViolationException noState = new DataIntegrityViolationException("no state");
		assertThatThrownBy(() -> failingWith(noState).createIfAbsent(USER)).isSameAs(noState);
	}

	@Test
	void 정상이면_예외가_없다() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
		assertThatCode(() -> new UserRepository(jdbc).createIfAbsent(USER)).doesNotThrowAnyException();
	}
}
