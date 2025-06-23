package com.timetable.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "timetable")
public class Timetable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "faculty_id", nullable = false)
    private Faculty faculty;
    
    @ManyToOne
    @JoinColumn(name = "section_id")
    private Section section;
    
    @ManyToOne
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;
    
    @ManyToOne
    @JoinColumn(name = "timeslot_id", nullable = false)
    private Timeslot timeslot;
    
    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    @ManyToMany
    @JoinTable(
        name = "timetable_sections",
        joinColumns = @JoinColumn(name = "timetable_id"),
        inverseJoinColumns = @JoinColumn(name = "section_id")
    )
    private java.util.List<Section> sections;
} 