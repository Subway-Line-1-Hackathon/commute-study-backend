package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.user.UserResponse;
import com.likelion.commute_study_api.user.UserService;
import com.likelion.commute_study_api.user.UserWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "사용자", description = "내 정보, 닉네임·통학 설정")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users")
@ApiResponse(responseCode = "401", description = "인증 실패", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"code\":\"UNAUTHORIZED\",\"message\":\"인증에 실패했습니다.\",\"details\":null}")))
public class UserController {

    private final UserService userService;
    private final UserWithdrawalService withdrawalService;

    public UserController(UserService userService, UserWithdrawalService withdrawalService) {
        this.userService = userService;
        this.withdrawalService = withdrawalService;
    }

    @Operation(summary = "내 정보 조회", description = "최초 호출 시 닉네임 없는 사용자를 자동 생성합니다.")
    @GetMapping("/me")
    @ApiResponse(responseCode = "200", description = "내 정보 조회 성공")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.getMe(jwt);
    }

    @Operation(summary = "회원 탈퇴", description = """
            로그인 계정과 모든 학습 기록(과목, 자료, 단원, 문제, 세션, 답안, 오답노트, AI 질문 횟수)을 **즉시 삭제**합니다.
            **되돌릴 수 없습니다.** 앱에서는 확인 창을 띄운 뒤 호출해 주세요.

            1. 업로드한 자료 원본(PDF·PPTX·PNG·JPG)을 저장소에서 삭제합니다. 일부 파일 삭제에 실패해도 탈퇴는 계속됩니다.
            2. 로그인 계정을 삭제합니다. 계정과 연결된 모든 데이터가 함께 삭제됩니다.

            - 성공(204) 후 프론트는 `supabase.auth.signOut()`으로 로그아웃하고 첫 화면으로 이동합니다.
              탈퇴한 계정의 토큰으로 다른 API를 호출하면 401입니다.
            - 실패(502)하면 계정과 데이터가 남아 있습니다. 같은 요청을 다시 보내면 됩니다.
            - 같은 구글 계정으로 다시 로그인하면 새 회원으로 시작합니다.
            """)
    @ApiResponse(responseCode = "204", description = "탈퇴 완료(본문 없음)", content = @Content)
    @ApiResponse(responseCode = "502", description = "ACCOUNT_DELETE_FAILED: 로그인 계정을 삭제하지 못함. 다시 시도",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                    "{\"code\":\"ACCOUNT_DELETE_FAILED\",\"message\":\"회원 탈퇴를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.\",\"details\":null}")))
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal Jwt jwt) {
        withdrawalService.withdraw(jwt);
    }
}
