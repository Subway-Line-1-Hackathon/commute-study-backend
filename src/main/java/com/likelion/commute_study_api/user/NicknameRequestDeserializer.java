package com.likelion.commute_study_api.user;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;

/** Reject scalar-to-string coercion locally, without changing other API JSON contracts. */
public class NicknameRequestDeserializer extends ValueDeserializer<NicknameRequest> {
    @Override
    public NicknameRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode body = context.readTree(parser);
        if (!body.isObject()) {
            return context.reportInputMismatch(NicknameRequest.class, "JSON object required");
        }
        JsonNode value = body.get("nickname");
        if (value != null && !value.isNull() && !value.isString()) {
            return context.reportInputMismatch(NicknameRequest.class, "nickname must be a string");
        }
        return new NicknameRequest(value == null || value.isNull() ? null : value.stringValue());
    }
}
