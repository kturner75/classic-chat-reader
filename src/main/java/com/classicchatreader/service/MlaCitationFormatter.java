package com.classicchatreader.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class MlaCitationFormatter {

    public record Metadata(
            String author,
            String title,
            String publisher,
            Integer publicationYear,
            String sourceTitle,
            String containerTitle,
            String url,
            LocalDate accessedDate
    ) {}

    public String format(Metadata metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");

        List<String> segments = new ArrayList<>();

        String authorSegment = formatAuthor(metadata.author());
        if (!authorSegment.isBlank()) {
            segments.add(withTerminalPeriod(authorSegment));
        }

        String title = clean(metadata.title());
        if (title.isBlank()) {
            title = "Untitled work";
        }
        segments.add(withTerminalPeriod(title));

        String publication = joinNonBlank(", ", clean(metadata.publisher()), formatYear(metadata.publicationYear()));
        if (!publication.isBlank()) {
            segments.add(withTerminalPeriod(publication));
        }

        String sourceTitle = clean(metadata.sourceTitle());
        if (!sourceTitle.isBlank()) {
            segments.add(withTerminalPeriod(sourceTitle));
        }

        String containerTitle = clean(metadata.containerTitle());
        if (!containerTitle.isBlank()) {
            segments.add(withTerminalPeriod(containerTitle));
        }

        String url = clean(metadata.url());
        if (!url.isBlank()) {
            segments.add(withTerminalPeriod(url));
        }

        if (metadata.accessedDate() != null) {
            segments.add(withTerminalPeriod("Accessed " + formatAccessDate(metadata.accessedDate())));
        }

        return String.join(" ", segments).trim();
    }

    private String formatAuthor(String author) {
        String normalized = clean(author);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.contains(",")) {
            return normalized;
        }

        List<String> parts = Arrays.stream(normalized.split("\\s+"))
                .filter(part -> !part.isBlank())
                .toList();
        if (parts.isEmpty()) {
            return "";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }

        int lastIndex = parts.size() - 1;
        String possibleSuffix = parts.get(lastIndex);
        if (parts.size() >= 3 && isNameSuffix(possibleSuffix)) {
            String surname = parts.get(lastIndex - 1);
            String given = String.join(" ", parts.subList(0, lastIndex - 1));
            return surname + ", " + given + ", " + normalizeSuffix(possibleSuffix);
        }

        String surname = parts.get(lastIndex);
        String given = String.join(" ", parts.subList(0, lastIndex));
        return surname + ", " + given;
    }

    private String joinNonBlank(String delimiter, String... values) {
        return Arrays.stream(values)
                .map(this::clean)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(delimiter));
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String formatYear(Integer publicationYear) {
        if (publicationYear == null || publicationYear <= 0) {
            return "";
        }
        return publicationYear.toString();
    }

    private String withTerminalPeriod(String value) {
        if (value.endsWith(".") || value.endsWith("!") || value.endsWith("?")) {
            return value;
        }
        return value + ".";
    }

    private boolean isNameSuffix(String value) {
        String normalized = clean(value).replace(".", "").toUpperCase();
        return normalized.equals("JR")
                || normalized.equals("SR")
                || normalized.equals("II")
                || normalized.equals("III")
                || normalized.equals("IV")
                || normalized.equals("V");
    }

    private String normalizeSuffix(String value) {
        String normalized = clean(value).replace(".", "").toUpperCase();
        return switch (normalized) {
            case "JR" -> "Jr.";
            case "SR" -> "Sr.";
            default -> normalized;
        };
    }

    private String formatAccessDate(LocalDate date) {
        return date.getDayOfMonth() + " " + date.getMonth().name().charAt(0)
                + date.getMonth().name().substring(1).toLowerCase() + " " + date.getYear();
    }
}
