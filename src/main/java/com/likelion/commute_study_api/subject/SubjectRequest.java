package com.likelion.commute_study_api.subject;

import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = SubjectRequestDeserializer.class)
public record SubjectRequest(@NotBlank String name, LocalDate examDate) {
    public SubjectRequest {
        name = name == null ? null : name.strip();
    }

    @JsonIgnore
    @AssertTrue(message = "과목명은 1~20자로 입력해주세요.")
    public boolean isNameValid() {
        return name == null || name.codePointCount(0, name.length()) <= 20;
    }
}

