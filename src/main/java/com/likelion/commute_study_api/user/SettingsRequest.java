package com.likelion.commute_study_api.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = SettingsRequestDeserializer.class)
public record SettingsRequest(
        @NotNull String nickname,
        @NotNull @Min(10) @Max(120) Integer oneWayMin,
        @Size(max = 50) String fromStation,
        @Size(max = 50) String toStation) {

    public SettingsRequest {
        nickname = NicknamePolicy.normalize(nickname);
    }

    @JsonIgnore
    @AssertTrue(message = "닉네임은 2~10자로 입력해주세요.")
    public boolean isNicknameValid() {
        if (nickname == null) return true; // @NotNull handles missing values.
        return NicknamePolicy.valid(nickname);
    }
}
