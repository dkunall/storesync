package com.storesync.shared.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;

/** For filters, which run before the MVC exception handler. */
public final class ProblemWriter {

    private ProblemWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper json, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ProblemHandler.problem(status, code, detail));
    }
}
