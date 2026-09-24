package com.classicchatreader.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/**
 * The export/replace command line shared by per-book production transfers (roster, art style,
 * curated membership). Exit 0 writes a receipt, 2 means the plan is stale, 1 is any other failure.
 */
final class BookTransferCli {
    private static final ObjectMapper JSON = new ObjectMapper();
    private BookTransferCli() {}

    @FunctionalInterface interface Export<S> { S run(Connection c, String source, String sourceId) throws Exception; }
    @FunctionalInterface interface Apply<P, S> { S run(Connection c, P plan) throws Exception; }

    /**
     * One transfer. {@code name} starts every message ("Roster exported.", "Roster operation failed: ...")
     * and {@code tempPrefix} names the receipt's temporary file.
     */
    record Transfer<P, S>(String name, String tempPrefix, Class<P> planType,
                          Function<P, String> planSource, Function<P, String> planSourceId,
                          Export<S> export, Apply<P, S> apply, Class<? extends Exception> staleType) {}

    static <P, S> int run(String[] args, PrintStream out, PrintStream err, Transfer<P, S> transfer) {
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
            P plan = replace ? JSON.readValue(Path.of(required(options, "--input")).toFile(), transfer.planType()) : null;
            if (plan != null && (!source.equals(transfer.planSource().apply(plan)) || !sourceId.equals(transfer.planSourceId().apply(plan))))
                throw new IllegalArgumentException("Plan does not match the selected book");
            String url = options.getOrDefault("--db-url", System.getenv("PDR_DATABASE_URL"));
            if (url == null || url.isBlank()) throw new IllegalArgumentException("Database connection is required");
            String user = options.getOrDefault("--db-user", options.containsKey("--db-url") ? "sa" : Objects.toString(System.getenv("PDR_DATABASE_USERNAME"), "sa"));
            String password = options.getOrDefault("--db-password", options.containsKey("--db-url") ? "" : Objects.toString(System.getenv("PDR_DATABASE_PASSWORD"), ""));
            // Create the result location before starting a write transaction.
            Path parent = output.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, transfer.tempPrefix(), ".json");
            try {
                S result;
                try (Connection c = DriverManager.getConnection(CacheTransferRunner.normalizeDbUrl(url), user, password)) {
                    result = replace ? transfer.apply().run(c, plan) : transfer.export().run(c, source, sourceId);
                }
                JSON.writeValue(temp.toFile(), result);
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
                out.println(transfer.name() + (replace ? " replaced and verified." : " exported."));
            } finally { Files.deleteIfExists(temp); }
            return 0;
        } catch (Exception e) {
            if (transfer.staleType().isInstance(e)) { err.println(e.getMessage()); return 2; }
            if (e instanceof IllegalArgumentException) { err.println(e.getMessage()); return 1; }
            err.println(transfer.name() + " operation failed: " + e.getMessage()
                    + ". Re-export the destination before retrying; no success receipt was written.");
            return 1;
        }
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}
