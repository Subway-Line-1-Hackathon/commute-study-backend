package com.likelion.commute_study_api.user;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** Keep strict JSON types local to this API without changing global coercion settings. */
public class SettingsRequestDeserializer extends ValueDeserializer<SettingsRequest> {
    @Override
    public SettingsRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode body = context.readTree(parser);
        if (!body.isObject()) {
            return context.reportInputMismatch(SettingsRequest.class, "JSON object required");
        }
        JsonNode minutes = body.get("oneWayMin");
        if (minutes != null && !minutes.isNull()
                && (!minutes.isIntegralNumber() || !minutes.canConvertToInt())) {
            return context.reportInputMismatch(SettingsRequest.class, "oneWayMin must be an integer");
        }
        return new SettingsRequest(text(body, "nickname", context),
                minutes == null || minutes.isNull() ? null : minutes.intValue(),
                text(body, "fromStation", context), text(body, "toStation", context));
    }

    private String text(JsonNode body, String field, DeserializationContext context) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) {
            return context.reportInputMismatch(SettingsRequest.class, "%s must be a string", field);
        }
        return value.stringValue();
    }
}
