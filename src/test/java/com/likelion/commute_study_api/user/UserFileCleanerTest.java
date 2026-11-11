package com.likelion.commute_study_api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.material.MaterialErrorCode;
import com.likelion.commute_study_api.material.MaterialRepository;
import com.likelion.commute_study_api.material.storage.MaterialStorage;

/** 회원 탈퇴(#67) 때 Storage 자료 원본 정리. */
class UserFileCleanerTest {

	private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Test
	void 모든_파일을_지우고_개수를_돌려준다() {
		MaterialRepository repository = mock(MaterialRepository.class);
		MaterialStorage storage = mock(MaterialStorage.class);
		when(repository.fileReferencesOfUser(USER)).thenReturn(List.of("b/u/a.pdf", "b/u/p1.png"));

		var result = new UserFileCleaner(repository, storage).deleteAll(USER);

		assertThat(result).isEqualTo(new UserFileCleaner.Result(2, 0));
		verify(storage).delete("b/u/a.pdf");
		verify(storage).delete("b/u/p1.png");
	}

	@Test
	void 일부가_실패해도_나머지를_계속_지운다() {
		MaterialRepository repository = mock(MaterialRepository.class);
		MaterialStorage storage = mock(MaterialStorage.class);
		when(repository.fileReferencesOfUser(USER)).thenReturn(List.of("b/u/a.pdf", "b/u/b.pdf", "b/u/c.pdf"));
		doThrow(new ApiException(MaterialErrorCode.STORAGE_UNAVAILABLE)).when(storage).delete("b/u/b.pdf");

		var result = new UserFileCleaner(repository, storage).deleteAll(USER);

		assertThat(result).isEqualTo(new UserFileCleaner.Result(3, 1));
		verify(storage).delete("b/u/c.pdf");
	}

	@Test
	void 파일이_없으면_Storage를_부르지_않는다() {
		MaterialRepository repository = mock(MaterialRepository.class);
		MaterialStorage storage = mock(MaterialStorage.class);
		when(repository.fileReferencesOfUser(USER)).thenReturn(List.of());

		assertThat(new UserFileCleaner(repository, storage).deleteAll(USER)).isEqualTo(new UserFileCleaner.Result(0, 0));
		verify(storage, never()).delete(org.mockito.ArgumentMatchers.anyString());
	}

	/** 실제 SQL을 격리된 H2에서 실행해 경로 수집 범위를 확인한다. */
	@Nested
	class 경로_수집 {

		private JdbcTemplate jdbc;
		private MaterialRepository repository;

		@BeforeEach
		void setUp() {
			var dataSource = new DriverManagerDataSource(
					"jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
			new ResourceDatabasePopulator(
					new ClassPathResource("material-test-schema.sql"),
					new ClassPathResource("db/migration/V4__add_material_file.sql")).execute(dataSource);
			jdbc = new JdbcTemplate(dataSource);
			repository = new MaterialRepository(jdbc);
			for (UUID user : List.of(USER, OTHER)) {
				jdbc.update("INSERT INTO app_user(id) VALUES (?)", user);
			}
		}

		private long subject(UUID user) {
			jdbc.update("INSERT INTO subject(user_id, name) VALUES (?, '과목')", user);
			return jdbc.queryForObject("SELECT max(id) FROM subject", Long.class);
		}

		private long material(long subject, String url, String type, String status) {
			jdbc.update("INSERT INTO material(subject_id, file_url, file_type, status) VALUES (?,?,?,?)",
					subject, url, type, status);
			return jdbc.queryForObject("SELECT max(id) FROM material", Long.class);
		}

		@Test
		void 내_자료의_PDF와_PNG_전체를_중복_없이_모은다() {
			long mine = subject(USER);
			long second = subject(USER);
			material(mine, "b/u/a.pdf", "PDF", "READY");
			material(second, "b/u/c.pptx", "PPT", "PROCESSING");
			long png = material(mine, "b/u/p1.png", "PNG", "FAILED"); // file_url은 첫 장과 같다
			jdbc.update("INSERT INTO material_file VALUES (?,1,'b/u/p1.png'),(?,2,'b/u/p2.png')", png, png);
			long expired = material(mine, "", "PNG", "EXPIRED"); // 이미 정리된 자료는 경로가 빈 문자열
			jdbc.update("INSERT INTO material_file VALUES (?,1,'')", expired);
			material(subject(OTHER), "b/o/x.pdf", "PDF", "READY");

			assertThat(repository.fileReferencesOfUser(USER))
					.containsExactly("b/u/a.pdf", "b/u/c.pptx", "b/u/p1.png", "b/u/p2.png");
			assertThat(repository.fileReferencesOfUser(OTHER)).containsExactly("b/o/x.pdf");
		}

		@Test
		void 자료가_없으면_빈_목록() {
			subject(USER);
			assertThat(repository.fileReferencesOfUser(USER)).isEmpty();
		}
	}
}
