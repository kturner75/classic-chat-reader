package com.classicchatreader.controller;

import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.service.ClassroomContextService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/classroom")
public class ClassroomController {

    private final ClassroomContextService classroomContextService;

    public ClassroomController(ClassroomContextService classroomContextService) {
        this.classroomContextService = classroomContextService;
    }

    @GetMapping("/context")
    public ClassroomContextResponse getContext() {
        return classroomContextService.getContext();
    }
}
