package com.classicchatreader.controller;

import org.apache.lucene.queryparser.classic.ParseException;
import com.classicchatreader.service.SearchService;
import com.classicchatreader.service.SearchService.SearchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ResponseEntity<List<SearchResult>> search(
            @RequestParam String q,
            @RequestParam(required = false) String bookId,
            @RequestParam(required = false) String chapterId,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            List<SearchResult> results = searchService.search(q, bookId, chapterId, limit);
            return ResponseEntity.ok(results);
        } catch (IOException | ParseException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
