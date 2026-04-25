package com.classicchatreader.controller;

import com.classicchatreader.model.GenerationJobStatusResponse;
import com.classicchatreader.service.GenerationJobStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/generation")
public class GenerationStatusController {

    private final GenerationJobStatusService generationJobStatusService;

    public GenerationStatusController(GenerationJobStatusService generationJobStatusService) {
        this.generationJobStatusService = generationJobStatusService;
    }

    @GetMapping("/status")
    public GenerationJobStatusResponse getGlobalStatus() {
        return generationJobStatusService.getGlobalStatus();
    }

    @GetMapping("/book/{bookId}/status")
    public GenerationJobStatusResponse getBookStatus(@PathVariable String bookId) {
        return generationJobStatusService.getBookStatus(bookId);
    }
}
