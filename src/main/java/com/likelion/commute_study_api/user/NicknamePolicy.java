package com.likelion.commute_study_api.user;

/** Shared legacy/settings nickname rules; preserve trim(), not a new whitespace policy. */
public final class NicknamePolicy {
    private NicknamePolicy() {}
    public static String normalize(String value) {
        return value == null ? null : value.trim();
    }

    public static boolean valid(String value) {
        if (value == null) return false;
        int length = value.codePointCount(0, value.length());
        return length >= 2 && length <= 10;
    }
}
