package com.classicchatreader.cli;

import com.classicchatreader.roster.RosterTransfer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Deliberately separate from portrait/cache transfer. Never touches image files or Spaces. */
public final class RosterTransferRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RosterTransferRunner() {}
    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }
    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || !Set.of("export", "replace").contains(args[0])) throw new IllegalArgumentException("Use export or replace");
            Map<String, String> options = new HashMap<>();
            boolean apply = false;
            for (int i = 1; i < args.length; i++) {
                String key = args[i];
                if ("--apply".equals(key)) { if (apply) throw new IllegalArgumentException("Duplicate --apply"); apply = true; continue; }
                if (!Set.of("--source", "--source-id", "--input", "--output", "--db-url", "--db-user", "--db-password").contains(key) || i + 1 >= args.length || options.containsKey(key)) throw new IllegalArgumentException("Invalid or duplicate argument");
                options.put(key, args[++i]);
            }
            String source = required(options, "--source"), sourceId = required(options, "--source-id");
            Path output = Path.of(required(options, "--output"));
            boolean replace = "replace".equals(args[0]);
            if (replace != apply) throw new IllegalArgumentException("Only replace requires --apply");
            RosterTransfer.Plan plan = replace ? JSON.readValue(Path.of(required(options, "--input")).toFile(), RosterTransfer.Plan.class) : null;
            if (plan != null && (!source.equals(plan.source()) || !sourceId.equals(plan.sourceId()))) throw new IllegalArgumentException("Plan does not match the selected book");
            String url = options.getOrDefault("--db-url", System.getenv("PDR_DATABASE_URL"));
            if (url == null || url.isBlank()) throw new IllegalArgumentException("Database connection is required");
            String user = options.getOrDefault("--db-user", options.containsKey("--db-url") ? "sa" : Objects.toString(System.getenv("PDR_DATABASE_USERNAME"), "sa"));
            String password = options.getOrDefault("--db-password", options.containsKey("--db-url") ? "" : Objects.toString(System.getenv("PDR_DATABASE_PASSWORD"), ""));
            // Create the result location before starting a write transaction.
            Path parent = output.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, "roster-", ".json");
            try {
                RosterTransfer.Snapshot result;
                try (Connection c = DriverManager.getConnection(CacheTransferRunner.normalizeDbUrl(url), user, password)) {
                    result = replace ? RosterTransfer.apply(c, plan) : RosterTransfer.exportRoster(c, source, sourceId);
                }
                JSON.writeValue(temp.toFile(), result);
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
                out.println(replace ? "Roster replaced and verified." : "Roster exported.");
            } finally { Files.deleteIfExists(temp); }
            return 0;
        } catch (RosterTransfer.StalePlan e) {
            err.println(e.getMessage()); return 2;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage()); return 1;
        } catch (Exception e) {
            err.println("Roster operation failed. Re-export the destination before retrying; no success receipt was written."); return 1;
        }
    }
    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}
