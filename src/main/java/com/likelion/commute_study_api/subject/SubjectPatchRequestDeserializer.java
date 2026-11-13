package com.likelion.commute_study_api.subject;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

public class SubjectPatchRequestDeserializer extends ValueDeserializer<SubjectPatchRequest> {
    @Override
    public SubjectPatchRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode body = context.readTree(parser);
        if (!body.isObject()) return context.reportInputMismatch(SubjectPatchRequest.class, "JSON object required");
        JsonNode name = body.get("name");
        JsonNode date = body.get("examDate");
        return new SubjectPatchRequest(name != null, SubjectRequestDeserializer.readName(name, context),
                date != null, SubjectRequestDeserializer.readExamDate(date, context));
    }
}
