package com.likelion.commute_study_api.user;

import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = NicknameRequestDeserializer.class)
public record NicknameRequest(String nickname) {}
