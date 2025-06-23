package com.timetable.controller;

import com.timetable.model.Timetable;
import com.timetable.service.TimetableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.Collections;

@RestController
@RequestMapping("/api")
public class TimetableController {
    @Autowired
    private TimetableService timetableService;
    
    @GetMapping("/reference-data")
    public ResponseEntity<Map<String, List<?>>> getReferenceData() {
        return ResponseEntity.ok(timetableService.getReferenceData());
    }
    
    @PostMapping("/generate-timetable")
    public ResponseEntity<Map<String, Object>> generateTimetable(@RequestBody List<Map<String, Object>> subjects) {
        return timetableService.generateTimetable(subjects);
    }
    
    @GetMapping("/timetable")
    public ResponseEntity<List<Map<String, Object>>> getTimetable() {
        return ResponseEntity.ok(Collections.emptyList()); 
    }
} 