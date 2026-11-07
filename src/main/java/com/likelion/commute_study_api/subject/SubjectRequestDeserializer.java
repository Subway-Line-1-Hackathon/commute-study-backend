package com.likelion.commute_study_api.subject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

public class SubjectRequestDeserializer extends ValueDeserializer<SubjectRequest> {
    @Override
    public SubjectRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode body = context.readTree(parser);
        if (!body.isObject()) return context.reportInputMismatch(SubjectRequest.class, "JSON object required");
        JsonNode name = body.get("name");
        if (name != null && !name.isNull() && !name.isString()) {
            return context.reportInputMismatch(SubjectRequest.class, "name must be a string");
        }
        JsonNode date = body.get("examDate");
        LocalDate examDate = null;
        if (date != null && !date.isNull()) {
            if (!date.isString() || !date.stringValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                return context.reportInputMismatch(SubjectRequest.class, "examDate must be YYYY-MM-DD");
            }
            try {
                examDate = LocalDate.parse(date.stringValue());
                if (examDate.getYear() == 0) {
                    return context.reportInputMismatch(SubjectRequest.class, "examDate year must be 0001 through 9999");
                }
            } catch (DateTimeParseException e) {
                return context.reportInputMismatch(SubjectRequest.class, "examDate must be a valid date");
            }
        }
        return new SubjectRequest(name == null || name.isNull() ? null : name.stringValue(), examDate);
    }
}

