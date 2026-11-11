package com.likelion.commute_study_api.user;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.likelion.commute_study_api.common.auth.CurrentUser;

/**
 * 회원 탈퇴(#67). 즉시 삭제하며 되돌릴 수 없다.
 *
 * <ol>
 *   <li>Storage 자료 원본 삭제: 계정을 지우면 DB에서 경로를 찾을 수 없으므로 먼저 한다. 일부 실패해도 계속한다.</li>
 *   <li>Supabase 로그인 계정(auth.users) 삭제: app_user부터 모든 사용자 데이터가 FK on delete cascade로 지워진다.
 *       실패하면 502이고 계정·데이터는 남는다. 다시 호출하면 이어서 처리된다(이미 지운 파일·계정은 성공으로 본다).</li>
 * </ol>
 *
 * 외부 HTTP 호출이 있으므로 DB 트랜잭션으로 묶지 않는다. 회원 행을 새로 만들지 않도록 ensureUser를 쓰지 않는다.
 */
@Service
public class UserWithdrawalService {

	private static final Logger log = LoggerFactory.getLogger(UserWithdrawalService.class);

	private final CurrentUser currentUser;
	private final UserFileCleaner fileCleaner;
	private final SupabaseAuthAdminClient authAdminClient;

	public UserWithdrawalService(CurrentUser currentUser, UserFileCleaner fileCleaner,
			SupabaseAuthAdminClient authAdminClient) {
		this.currentUser = currentUser;
		this.fileCleaner = fileCleaner;
		this.authAdminClient = authAdminClient;
	}

	public void withdraw(Jwt jwt) {
		UUID userId = currentUser.authenticatedId(jwt);
		long start = System.currentTimeMillis();

		UserFileCleaner.Result files = fileCleaner.deleteAll(userId);
		SupabaseAuthAdminClient.Result account = authAdminClient.deleteUser(userId);

		log.info("[회원] 탈퇴 완료 userId={} account={} files={} fileFailed={} elapsedMs={}",
				userId, account, files.total(), files.failed(), System.currentTimeMillis() - start);
	}
}
