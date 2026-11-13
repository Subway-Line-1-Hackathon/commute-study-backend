package com.likelion.commute_study_api.subject;

import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Presence flags distinguish an omitted field from an explicit JSON null. */
@JsonDeserialize(using = SubjectPatchRequestDeserializer.class)
public record SubjectPatchRequest(@Schema(hidden = true) boolean nameProvided, String name,
                                  @Schema(hidden = true) boolean examDateProvided,
                                  @Schema(nullable = true, description = "시험일. null이면 삭제하고 생략하면 유지합니다.") LocalDate examDate) {
    public SubjectPatchRequest {
        name = name == null ? null : name.strip();
    }

    @JsonIgnore
    @AssertTrue(message = "과목명은 1~20자로 입력해주세요.")
    public boolean isNameValid() {
        return !nameProvided || (name != null && !name.isBlank() && name.codePointCount(0, name.length()) <= 20);
    }
}
