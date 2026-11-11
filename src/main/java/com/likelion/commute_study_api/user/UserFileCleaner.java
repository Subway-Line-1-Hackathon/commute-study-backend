package com.likelion.commute_study_api.user;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.likelion.commute_study_api.material.MaterialRepository;
import com.likelion.commute_study_api.material.storage.MaterialStorage;

/**
 * 회원 탈퇴(#67) 때 사용자의 Storage 자료 원본을 지운다. DB 연쇄 삭제로는 Storage 파일이 지워지지 않기 때문이다.
 *
 * <p>파일 하나가 실패해도 나머지를 계속 지우고, 실패 수만 돌려준다. 탈퇴 자체를 막지 않는다는 팀 결정에 따른다.
 * 계정 삭제 전에 불러야 한다(삭제 후에는 DB에서 경로를 찾을 수 없다). DB 트랜잭션 밖에서 부른다.
 */
@Component
public class UserFileCleaner {

	private static final Logger log = LoggerFactory.getLogger(UserFileCleaner.class);

	private final MaterialRepository materialRepository;
	private final MaterialStorage storage;

	public UserFileCleaner(MaterialRepository materialRepository, MaterialStorage storage) {
		this.materialRepository = materialRepository;
		this.storage = storage;
	}

	public Result deleteAll(UUID userId) {
		List<String> references = materialRepository.fileReferencesOfUser(userId);
		int failed = 0;
		for (String reference : references) {
			try {
				storage.delete(reference); // 이미 없는 파일(NoSuchKey)은 성공으로 본다
			} catch (Exception e) {
				failed++;
			}
		}
		if (failed > 0) {
			log.warn("[회원] 자료 원본 일부 삭제 실패 userId={} total={} failed={}", userId, references.size(), failed);
		}
		return new Result(references.size(), failed);
	}

	public record Result(int total, int failed) {
	}
}
