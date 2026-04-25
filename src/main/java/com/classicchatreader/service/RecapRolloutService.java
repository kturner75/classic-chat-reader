package com.classicchatreader.service;

import jakarta.annotation.PostConstruct;
import com.classicchatreader.entity.ChapterRecapStatus;
import com.classicchatreader.repository.ChapterRecapRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RecapRolloutService {

    private static final Logger log = LoggerFactory.getLogger(RecapRolloutService.class);

    private final ChapterRecapRepository chapterRecapRepository;

    @Value("${recap.rollout.mode:all}")
    private String rolloutMode;

    @Value("${recap.rollout.allowed-book-ids:}")
    private String allowedBookIdsRaw;

    private Set<String> allowedBookIds = Set.of();

    public RecapRolloutService(ChapterRecapRepository chapterRecapRepository) {
        this.chapterRecapRepository = chapterRecapRepository;
    }

    @PostConstruct
    void init() {
        allowedBookIds = Arrays.stream(allowedBookIdsRaw.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        log.info("Recap rollout mode: {} (allow-list size: {})", getRolloutMode(), allowedBookIds.size());
    }

    public boolean isBookAllowed(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return false;
        }

        return switch (getRolloutMode()) {
            case "all" -> true;
            case "allow-list" -> allowedBookIds.contains(bookId);
            case "pre-generated" ->
                    chapterRecapRepository.existsByChapterBookIdAndStatus(bookId, ChapterRecapStatus.COMPLETED);
            default -> {
                log.warn("Unknown recap rollout mode '{}'; denying recap availability", rolloutMode);
                yield false;
            }
        };
    }

    public String getRolloutMode() {
        String normalized = rolloutMode == null ? "" : rolloutMode.trim().toLowerCase();
        if (normalized.equals("allowlist")) {
            return "allow-list";
        }
        if (normalized.equals("pregenerated")) {
            return "pre-generated";
        }
        return normalized.isEmpty() ? "all" : normalized;
    }

    public int getAllowListSize() {
        return allowedBookIds.size();
    }
}
