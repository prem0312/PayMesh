package com.demo.upimesh;

import com.demo.upimesh.controller.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BridgeIngestionErrorHandlingTest {

    @Test
    void globalApiErrorHandlerDoesNotSerializeExceptionDetails() {
        var response = new ApiExceptionHandler()
                .handleUnexpectedFailure(new IllegalStateException("secret internal detail"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("internal_error", response.getBody().get("error"));
        assertFalse(response.getBody().toString().contains("secret internal detail"));
    }
}
