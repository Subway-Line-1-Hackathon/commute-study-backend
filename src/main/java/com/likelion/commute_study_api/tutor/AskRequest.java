package com.likelion.commute_study_api.tutor;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record AskRequest(@NotBlank String message, List<@NotNull @Valid Message> history) {
    public record Message(@NotNull @Pattern(regexp="user|assistant") String role, @NotNull String content) {}
}
