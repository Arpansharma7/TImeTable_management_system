package com.timetable.repository;

import com.timetable.model.Timetable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface TimetableRepository extends JpaRepository<Timetable, Long> {
    @Query("SELECT t FROM Timetable t WHERE t.faculty.id = ?1 AND t.timeslot.id = ?2")
    List<Timetable> findByFacultyAndTimeslot(Long facultyId, Long timeslotId);
    
    @Query("SELECT t FROM Timetable t WHERE t.section.id = ?1 AND t.timeslot.id = ?2")
    List<Timetable> findBySectionAndTimeslot(Long sectionId, Long timeslotId);
    
    @Query("SELECT t FROM Timetable t WHERE t.room.id = ?1 AND t.timeslot.id = ?2")
    List<Timetable> findByRoomAndTimeslot(Long roomId, Long timeslotId);
} 