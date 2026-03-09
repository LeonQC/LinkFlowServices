package com.linkflow.api.common.web;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.UUID;

public final class RequestIdContext {

    public static final String ATTRIBUTE_NAME = "request_id";
    public static final String HEADER_NAME = "X-Request-Id";

    private RequestIdContext() {
    }

    public static String getRequestId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return UUID.randomUUID().toString();
        }

        Object requestId = attributes.getAttribute(ATTRIBUTE_NAME, RequestAttributes.SCOPE_REQUEST);
        if (requestId instanceof String value && !value.isBlank()) {
            return value;
        }

        String generated = UUID.randomUUID().toString();
        attributes.setAttribute(ATTRIBUTE_NAME, generated, RequestAttributes.SCOPE_REQUEST);
        return generated;
    }
}
