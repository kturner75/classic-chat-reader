package com.classicchatreader.controller;

import com.classicchatreader.roster.RosterTransfer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.util.Set;

/** Local operator adapter. Production administration uses RosterTransferRunner. */
@RestController
@RequestMapping("/api/studio/roster")
public class StudioRosterController {
    private final DataSource dataSource;
    public StudioRosterController(DataSource dataSource) { this.dataSource = dataSource; }

    private void requireLocal(HttpServletRequest request) {
        if (!Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr()) ||
                request.getHeader("Forwarded") != null || request.getHeader("X-Forwarded-For") != null
                || request.getHeader("X-Real-IP") != null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Local Studio access required");
        try {
            String host = new URI(request.getRequestURL().toString()).getHost();
            if (!Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(host)) throw new IllegalArgumentException();
            String origin = request.getHeader("Origin");
            if (origin != null && !Set.of("http://localhost:5173", "http://127.0.0.1:5173").contains(origin)) throw new IllegalArgumentException();
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Local Studio access required"); }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> error(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(
                org.springframework.http.ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason()));
    }

    @GetMapping("/{source}/{sourceId}")
    public RosterTransfer.Snapshot get(@PathVariable String source, @PathVariable String sourceId, HttpServletRequest request) throws Exception {
        requireLocal(request);
        try (Connection c = dataSource.getConnection()) {
            return RosterTransfer.exportRoster(c, source, sourceId);
        } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }

    @PostMapping("/{source}/{sourceId}/replace")
    public RosterTransfer.Snapshot replace(@PathVariable String source, @PathVariable String sourceId,
            @RequestBody RosterTransfer.Plan plan, HttpServletRequest request) throws Exception {
        requireLocal(request);
        if (!source.equals(plan.source()) || !sourceId.equals(plan.sourceId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan does not match the book");
        try (Connection c = dataSource.getConnection()) {
            return RosterTransfer.apply(c, plan);
        } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); }
    }
}
