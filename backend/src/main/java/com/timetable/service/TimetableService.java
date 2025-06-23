package com.timetable.service;

import com.timetable.model.*;
import com.timetable.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TimetableService {
    @Autowired
    private FacultyRepository facultyRepository;
    
    @Autowired
    private RoomRepository roomRepository;
    
    @Autowired
    private SectionRepository sectionRepository;
    
    @Autowired
    private TimeslotRepository timeslotRepository;
    
    @Autowired
    private TimetableRepository timetableRepository;
    
    public Map<String, List<?>> getReferenceData() {
        Map<String, List<?>> referenceData = new HashMap<>();
        referenceData.put("faculty", facultyRepository.findAll());
        referenceData.put("rooms", roomRepository.findAll());
        referenceData.put("sections", sectionRepository.findAll());
        referenceData.put("timeslots", timeslotRepository.findAll());
        return referenceData;
    }
    
    public ResponseEntity<Map<String, Object>> generateTimetable(List<Map<String, Object>> subjectsInput) {
        timetableRepository.deleteAll();

        // Fetch all necessary reference data once
        List<Timeslot> allTimeslots = timeslotRepository.findAll();
        List<Room> allRooms = roomRepository.findAll();
        List<Faculty> allFaculties = facultyRepository.findAll();
        List<Section> allSections = sectionRepository.findAll();

        // Initialize tracking data structures
        List<Timetable> generatedTimetable = new ArrayList<>();
        List<Map<String, Object>> skippedSlots = new ArrayList<>();

        // In-memory conflict tracking
        Map<Long, Set<Long>> facultyBookedSlots = new HashMap<>();
        Map<Long, Set<Long>> sectionBookedSlots = new HashMap<>();
        Map<Long, Set<Long>> roomBookedSlots = new HashMap<>();
        Map<Long, Integer> facultyLectureLoad = new HashMap<>();
        Map<String, Set<String>> subjectSectionAssignedDays = new HashMap<>();
// For round -robin faculty assignment per subject
        Map<String, Integer> subjectFacultyRoundRobinIndex = new HashMap<>();
        
        // New: Track sections that have been combined for a subject (Key: "subjectName|sectionId")
        Set<String> combinedSectionsTracked = new HashSet<>();
        // New: Track remaining lectures needed for each subject-section pair
        // Key: "subjectName|sectionId" -> Integer (count of lectures still needed)
        Map<String, Integer> remainingLectures = new HashMap<>();
        // Helper map to store original subject data by key for easy lookup of duration and frequency
        Map<String, Map<String, Object>> subjectMetadata = new HashMap<>();
        Map<String, Set<String>> subjectAssignedDays = new HashMap<>(); // subjectName -> set of days

        // Pre-process subjectsInput to initialize remainingLectures and subjectMetadata
        for (Map<String, Object> subjectData : subjectsInput) {
            String subjectName = (String) subjectData.get("subjectName");
            Object sectionIdObj = subjectData.get("sectionId");
            Long sectionId = null;
            if (sectionIdObj != null) {
                try { sectionId = Long.valueOf(sectionIdObj.toString()); } catch (NumberFormatException e) { /* handled later */ }
            }
            
            if (subjectName != null && sectionId != null) {
                String key = subjectName + "|" + sectionId;
                remainingLectures.merge(key, 1, Integer::sum); // Increment count for each lecture unit
                
                // Store metadata if not already present (assuming frequency/duration are consistent for subject-section)
                if (!subjectMetadata.containsKey(key)) {
                    // Parse faculty IDs immediately to ensure correct type
                    List<Long> parsedFacultyIds = parseFacultyIds(subjectData.get("facultyIds"), subjectName, skippedSlots);
                    subjectMetadata.put(key, Map.of(
                        "duration", subjectData.get("duration"),
                        "frequency", subjectData.get("frequency"),
                        "facultyIds", parsedFacultyIds // Store the parsed list
                    ));
                }
            }
        }

        // Sort timeslots for deterministic processing and easy consecutive lookup
        allTimeslots.sort(Comparator.comparing(Timeslot::getDay).thenComparing(Timeslot::getStartTime));

        // Iterative scheduling loop
        boolean progressMade;
        int iterationCount = 0;
        final int MAX_ITERATIONS = 5; // Prevent infinite loops

        do {
            progressMade = false;
            iterationCount++;
            if (iterationCount > MAX_ITERATIONS) { 
                // If after MAX_ITERATIONS, we are still making progress, it implies a complex scenario 
                // or potential for infinite loops with highly constrained inputs. Break to prevent hangs.
                // Remaining lectures will be reported as skipped.
                break; 
            }

            // Shuffle the keys to avoid always prioritizing the same subject-section pairs
            List<String> keysToSchedule = new ArrayList<>(remainingLectures.keySet());
            Collections.shuffle(keysToSchedule);

            for (String key : keysToSchedule) {
                if (remainingLectures.getOrDefault(key, 0) <= 0) {
                    continue; // Already scheduled all lectures for this subject-section
                }

                // FIX: Skip if this section has already been combined
                if (combinedSectionsTracked.contains(key)) {
                    continue;
                }

                String[] parts = key.split("\\|");
                String subjectName = parts[0];
                Long sectionId = Long.valueOf(parts[1]);

                Map<String, Object> metadata = subjectMetadata.get(key);
                if (metadata == null) {
                    skippedSlots.add(Map.of("subject", subjectName, "sectionId", sectionId, "reason", "Missing subject metadata. Skipping."));
                    remainingLectures.put(key, 0);
                    continue;
                }

                int duration = Integer.parseInt(metadata.get("duration").toString());
                int frequency = Integer.parseInt(metadata.get("frequency").toString());
                List<Long> inputFacultyIds = (List<Long>) metadata.get("facultyIds");

                Section currentSection = sectionRepository.findById(sectionId).orElse(null);
                if (currentSection == null) {
                    skippedSlots.add(Map.of("subject", subjectName, "sectionId", sectionId, "reason", "Section not found. Skipping."));
                    remainingLectures.put(key, 0);
                    continue;
                }

                List<Faculty> eligibleFaculties = allFaculties.stream()
                                                .filter(f -> inputFacultyIds.contains(f.getId()))
                                                .collect(Collectors.toList());
                if (eligibleFaculties.isEmpty()) {
                    skippedSlots.add(Map.of("subject", subjectName, "section", currentSection.getName(), "reason", "No eligible faculties for subject. Skipping."));
                    remainingLectures.put(key, 0);
                    continue;
                }

                boolean assignedThisIteration = false;

                // 2-hour lecture: try to combine two sections in LT first
                if (duration == 2) {
                    Optional<Section> partnerSectionOpt = findCombinableSection(currentSection, subjectName, allSections, combinedSectionsTracked, remainingLectures);
                    if (partnerSectionOpt.isPresent()) {
                        Section partnerSection = partnerSectionOpt.get();
                        String partnerKey = subjectName + "|" + partnerSection.getId();
                        int facultyCount = eligibleFaculties.size();
                        int startIdx = subjectFacultyRoundRobinIndex.getOrDefault(subjectName, 0);
                        for (int offset = 0; offset < facultyCount; offset++) {
                            int idx = (startIdx + offset) % facultyCount;
                            Faculty faculty = eligibleFaculties.get(idx);
                            boolean combinedAssigned = tryAssignCombinedLectureWithFaculty_SubjectDayCheck(subjectName, currentSection, partnerSection, duration, faculty,
                                allTimeslots, allRooms, generatedTimetable, skippedSlots,
                                facultyBookedSlots, sectionBookedSlots, roomBookedSlots, facultyLectureLoad,
                                frequency, subjectSectionAssignedDays);
                            if (combinedAssigned) {
                                remainingLectures.merge(key, -1, Integer::sum);
                                remainingLectures.merge(partnerKey, -1, Integer::sum);
                                combinedSectionsTracked.add(key);
                                combinedSectionsTracked.add(partnerKey);
                                subjectFacultyRoundRobinIndex.put(subjectName, (idx + 1) % facultyCount);
                                progressMade = true;
                                break;
                            }
                        }
                        if (progressMade) continue;
                    }
                }

                // Fallback: assign individually (CR room)
                int facultyCount = eligibleFaculties.size();
                int startIdx = subjectFacultyRoundRobinIndex.getOrDefault(subjectName, 0);
                for (int offset = 0; offset < facultyCount; offset++) {
                    int idx = (startIdx + offset) % facultyCount;
                    Faculty faculty = eligibleFaculties.get(idx);
                    assignedThisIteration = tryAssignLectureWithFaculty_SubjectDayCheck(subjectName, currentSection, duration, faculty,
                        allTimeslots, allRooms, generatedTimetable, skippedSlots,
                        facultyBookedSlots, sectionBookedSlots, roomBookedSlots, facultyLectureLoad,
                        frequency, subjectSectionAssignedDays);
                    if (assignedThisIteration) {
                        remainingLectures.merge(key, -1, Integer::sum);
                        subjectFacultyRoundRobinIndex.put(subjectName, (idx + 1) % facultyCount);
                        progressMade = true;
                        break;
                    }
                }
            }
        } while (progressMade && remainingLectures.values().stream().anyMatch(count -> count > 0));

        // Final reporting for truly skipped slots
        remainingLectures.forEach((key, count) -> {
            if (count > 0) {
                String[] parts = key.split("\\|");
                String subjectName = parts[0];
                Long sectionId = Long.valueOf(parts[1]);
                Section section = allSections.stream().filter(s -> s.getId().equals(sectionId)).findFirst().orElse(null);
                skippedSlots.add(Map.of("subject", subjectName, "section", section != null ? section.getName() : "N/A", "reason", "Not enough available slots to fulfill all lectures for the week."));
            }
        });

        Map<String, Object> result = new HashMap<>();
        result.put("timetable", generatedTimetable);
        result.put("skippedSlots", skippedSlots);
        return ResponseEntity.ok(result);
    }

    // --- Helper Methods for Scheduling Logic ---

    private List<Long> parseFacultyIds(Object facultyIdsObj, String subjectName, List<Map<String, Object>> skippedSlots) {
        List<Long> facultyIds = new ArrayList<>();
        if (facultyIdsObj instanceof List) {
            for (Object fId : (List<?>) facultyIdsObj) {
                if (fId != null) {
                    try {
                        facultyIds.add(Long.valueOf(fId.toString()));
                    } catch (NumberFormatException e) {
                        skippedSlots.add(Map.of("subject", subjectName, "facultyId", fId.toString(), "reason", "Invalid facultyId format in list."));
                    }
                } else {
                    skippedSlots.add(Map.of("subject", subjectName, "facultyId", "null", "reason", "Null facultyId found in list."));
                }
            }
        } else if (facultyIdsObj != null) {
            try {
                facultyIds.add(Long.valueOf(facultyIdsObj.toString()));
            } catch (NumberFormatException e) {
                skippedSlots.add(Map.of("subject", subjectName, "facultyIds", facultyIdsObj.toString(), "reason", "Invalid facultyId format."));
            }
        }
        return facultyIds;
    }

    private boolean tryAssignLecture(String subjectName, Section section, int duration, List<Faculty> eligibleFaculties,
                                    List<Timeslot> allTimeslots, List<Room> allRooms, List<Timetable> generatedTimetable, List<Map<String, Object>> skippedSlots,
                                    Map<Long, Set<Long>> facultyBookedSlots, Map<Long, Set<Long>> sectionBookedSlots, Map<Long, Set<Long>> roomBookedSlots, Map<Long, Integer> facultyLectureLoad,
                                    int subjectFrequency, Map<String, Set<String>> subjectSectionAssignedDays, Map<String, Integer> facultyRoundRobinIndex) {

        // Use round-robin index for this subject-section
        String subjectSectionKey = subjectName + "|" + section.getId();
        // Sort eligible faculties by id for deterministic order
        eligibleFaculties.sort(Comparator.comparingLong(Faculty::getId));
        int facultyCount = eligibleFaculties.size();
        int startIdx = 0;
        if (facultyRoundRobinIndex.containsKey(subjectSectionKey)) {
            startIdx = facultyRoundRobinIndex.get(subjectSectionKey);
        }
        // Try each faculty in round-robin order
        for (int offset = 0; offset < facultyCount; offset++) {
            int idx = (startIdx + offset) % facultyCount;
            Faculty faculty = eligibleFaculties.get(idx);
            String preferredDaysStr = faculty.getPreferredDays();
            List<String> facultyPreferredDays = (preferredDaysStr != null && !preferredDaysStr.isEmpty()) ?
                                                Arrays.asList(preferredDaysStr.split(",")).stream().map(String::trim).collect(Collectors.toList()) : Collections.emptyList();

            List<Timeslot> availableTimeslotsForFaculty = new ArrayList<>();
            List<Timeslot> otherTimeslotsForFaculty = new ArrayList<>();

            for (Timeslot ts : allTimeslots) {
                boolean isPreferredDay = facultyPreferredDays.isEmpty() || facultyPreferredDays.stream().anyMatch(day -> day.equalsIgnoreCase(ts.getDay()));
                boolean isFacultyAvailable = !facultyBookedSlots.getOrDefault(faculty.getId(), Collections.emptySet()).contains(ts.getId());
                boolean isSectionAvailable = !sectionBookedSlots.getOrDefault(section.getId(), Collections.emptySet()).contains(ts.getId());

                boolean isDayAlreadyUsed = subjectFrequency > 1 && subjectSectionAssignedDays.getOrDefault(subjectSectionKey, Collections.emptySet()).contains(ts.getDay());

                if (isFacultyAvailable && isSectionAvailable && !isDayAlreadyUsed) {
                    if (isPreferredDay) {
                        availableTimeslotsForFaculty.add(ts);
                    } else {
                        otherTimeslotsForFaculty.add(ts);
                    }
                }
            }

            List<Timeslot> combinedAvailableTimeslots = new ArrayList<>(availableTimeslotsForFaculty);
            combinedAvailableTimeslots.addAll(otherTimeslotsForFaculty);

            List<Timeslot> consecutiveSlots = findConsecutiveTimeslots(combinedAvailableTimeslots, duration, faculty.getId(), section.getId(),
                                                                       facultyBookedSlots, sectionBookedSlots, allTimeslots);

            if (!consecutiveSlots.isEmpty()) {
                Room selectedRoom = getAvailableRoom(section.getStudentCount(), consecutiveSlots, roomBookedSlots, allRooms);
                if (selectedRoom != null) {
                    assignLecture(subjectName, faculty, selectedRoom, Arrays.asList(section), consecutiveSlots, generatedTimetable,
                                  facultyBookedSlots, sectionBookedSlots, roomBookedSlots, facultyLectureLoad);
                    subjectSectionAssignedDays.computeIfAbsent(subjectSectionKey, k -> new HashSet<>()).add(consecutiveSlots.get(0).getDay());
                    // Update round-robin index for next assignment
                    facultyRoundRobinIndex.put(subjectSectionKey, (idx + 1) % facultyCount);
                    return true;
                } else {
                    skippedSlots.add(Map.of("subject", subjectName, "section", section.getName(), "faculty", faculty.getName(), "reason", "No room available for the selected timeslots. (Check roomType/capacity in your data)"));
                }
            } else {
                 skippedSlots.add(Map.of("subject", subjectName, "section", section.getName(), "faculty", faculty.getName(), "reason", "No consecutive timeslots found for required duration or day already used for this faculty."));
            }
        }
        skippedSlots.add(Map.of("subject", subjectName, "section", section.getName(), "reason", "No eligible faculty could assign the lecture."));
        return false;
    }

    private boolean tryAssignCombinedLecture(String subjectName, Section section1, Section section2, int duration, List<Faculty> eligibleFaculties,
                                           List<Timeslot> allTimeslots, List<Room> allRooms, List<Timetable> generatedTimetable, List<Map<String, Object>> skippedSlots,
                                           Map<Long, Set<Long>> facultyBookedSlots, Map<Long, Set<Long>> sectionBookedSlots, Map<Long, Set<Long>> roomBookedSlots, Map<Long, Integer> facultyLectureLoad,
                                           int subjectFrequency, Map<String, Set<String>> subjectSectionAssignedDays) {

        int combinedCapacityNeeded = section1.getStudentCount() + section2.getStudentCount();
        if (combinedCapacityNeeded > 180) {
            skippedSlots.add(Map.of("subject", subjectName, "section1", section1.getName(), "section2", section2.getName(), "reason", "Combined capacity exceeds LT room capacity."));
            return false;
        }

        Collections.shuffle(eligibleFaculties);
        eligibleFaculties.sort(Comparator.comparingInt(f -> facultyLectureLoad.getOrDefault(f.getId(), 0)));
        
        String section1Key = subjectName + "|" + section1.getId();
        String section2Key = subjectName + "|" + section2.getId();

        for (Faculty faculty : eligibleFaculties) {
            String preferredDaysStr = faculty.getPreferredDays();
            List<String> facultyPreferredDays = (preferredDaysStr != null && !preferredDaysStr.isEmpty()) ?
                                                Arrays.asList(preferredDaysStr.split(",")).stream().map(String::trim).collect(Collectors.toList()) : Collections.emptyList();

            List<Timeslot> availableTimeslotsForFaculty = new ArrayList<>();
            List<Timeslot> otherTimeslotsForFaculty = new ArrayList<>();

            for (Timeslot ts : allTimeslots) {
                boolean isPreferredDay = facultyPreferredDays.isEmpty() || facultyPreferredDays.stream().anyMatch(day -> day.equalsIgnoreCase(ts.getDay()));
                boolean isFacultyAvailable = !facultyBookedSlots.getOrDefault(faculty.getId(), Collections.emptySet()).contains(ts.getId());
                boolean isSection1Available = !sectionBookedSlots.getOrDefault(section1.getId(), Collections.emptySet()).contains(ts.getId());
                boolean isSection2Available = !sectionBookedSlots.getOrDefault(section2.getId(), Collections.emptySet()).contains(ts.getId());

                boolean isDayAlreadyUsedForS1 = subjectFrequency > 1 && subjectSectionAssignedDays.getOrDefault(section1Key, Collections.emptySet()).contains(ts.getDay());
                boolean isDayAlreadyUsedForS2 = subjectFrequency > 1 && subjectSectionAssignedDays.getOrDefault(section2Key, Collections.emptySet()).contains(ts.getDay());

                if (isFacultyAvailable && isSection1Available && isSection2Available && !isDayAlreadyUsedForS1 && !isDayAlreadyUsedForS2) {
                    if (isPreferredDay) {
                        availableTimeslotsForFaculty.add(ts);
                    } else {
                        otherTimeslotsForFaculty.add(ts);
                    }
                }
            }

            List<Timeslot> combinedAvailableTimeslots = new ArrayList<>(availableTimeslotsForFaculty);
            combinedAvailableTimeslots.addAll(otherTimeslotsForFaculty);

            List<Timeslot> consecutiveSlots = findConsecutiveTimeslots(combinedAvailableTimeslots, duration, faculty.getId(), section1.getId(),
                                                                       facultyBookedSlots, sectionBookedSlots, allTimeslots);

            if (!consecutiveSlots.isEmpty()) {
                Room selectedRoom = getAvailableLtRoom(combinedCapacityNeeded, consecutiveSlots, roomBookedSlots, allRooms);
                if (selectedRoom != null) {
                    assignLecture(subjectName, faculty, selectedRoom, Arrays.asList(section1, section2), consecutiveSlots, generatedTimetable,
                                  facultyBookedSlots, sectionBookedSlots, roomBookedSlots, facultyLectureLoad);
                    
                    subjectSectionAssignedDays.computeIfAbsent(section1Key, k -> new HashSet<>()).add(consecutiveSlots.get(0).getDay());
                    subjectSectionAssignedDays.computeIfAbsent(section2Key, k -> new HashSet<>()).add(consecutiveSlots.get(0).getDay());

                    return true;
                } else {
                    skippedSlots.add(Map.of("subject", subjectName, "section1", section1.getName(), "section2", section2.getName(), "faculty", faculty.getName(), "reason", "No LT room available for combined sections."));
                }
            }
            else {
                skippedSlots.add(Map.of("subject", subjectName, "section1", section1.getName(), "section2", section2.getName(), "faculty", faculty.getName(), "reason", "No consecutive timeslots found for combined sections or day already used for this faculty."));
            }
        }
        skippedSlots.add(Map.of("subject", subjectName, "section1", section1.getName(), "section2", section2.getName(), "reason", "No eligible faculty could assign the combined lecture."));
        return false;
    }

    private Optional<Section> findCombinableSection(Section currentSection, String subjectName, List<Section> allSections, Set<String> combinedSectionsTracked, Map<String, Integer> remainingLectures) {
        // Allow combining with any section within a window of 3 before and 3 after in the section order
        int window = 3;
        int idx = -1;
        for (int i = 0; i < allSections.size(); i++) {
            if (allSections.get(i).getId().equals(currentSection.getId())) {
                idx = i;
                break;
            }
        }
        if (idx == -1) return Optional.empty();
        for (int offset = -window; offset <= window; offset++) {
            if (offset == 0) continue;
            int partnerIdx = idx + offset;
            if (partnerIdx < 0 || partnerIdx >= allSections.size()) continue;
            Section otherSection = allSections.get(partnerIdx);
            String otherSectionKey = subjectName + "|" + otherSection.getId();
            if (!currentSection.equals(otherSection) && !combinedSectionsTracked.contains(otherSectionKey)) {
                if (remainingLectures.getOrDefault(otherSectionKey, 0) > 0) {
                    return Optional.of(otherSection);
                }
            }
        }
        return Optional.empty();
    }

    private List<Timeslot> findConsecutiveTimeslots(List<Timeslot> availableTimeslots, int duration, Long facultyId, Long sectionId,
                                                   Map<Long, Set<Long>> facultyBookedSlots, Map<Long, Set<Long>> sectionBookedSlots, List<Timeslot> allTimeslots) {
        if (duration == 1) {
            // For 1-hour lectures, just find any available timeslot
            return availableTimeslots.stream()
                                     .filter(ts -> !facultyBookedSlots.getOrDefault(facultyId, Collections.emptySet()).contains(ts.getId()) &&
                                                    !sectionBookedSlots.getOrDefault(sectionId, Collections.emptySet()).contains(ts.getId()))
                                     .findFirst()
                                     .map(Arrays::asList)
                                     .orElse(Collections.emptyList());
        }

        // For multi-hour lectures, find consecutive slots
        for (int i = 0; i <= availableTimeslots.size() - duration; i++) {
            List<Timeslot> potentialConsecutiveSlots = new ArrayList<>();
            boolean isConsecutive = true;

            for (int j = 0; j < duration; j++) {
                Timeslot current = availableTimeslots.get(i + j);

                // Check if already booked for faculty or section
                if (facultyBookedSlots.getOrDefault(facultyId, Collections.emptySet()).contains(current.getId()) ||
                    sectionBookedSlots.getOrDefault(sectionId, Collections.emptySet()).contains(current.getId())) {
                    isConsecutive = false;
                    break;
                }

                potentialConsecutiveSlots.add(current);

                if (j < duration - 1) {
                    Timeslot next = availableTimeslots.get(i + j + 1);
                    // Check if timeslots are on the same day and are consecutive by time
                    if (!current.getDay().equalsIgnoreCase(next.getDay()) ||
                        !current.getEndTime().equals(next.getStartTime())) {
                        isConsecutive = false;
                        break;
                    }
                }
            }

            if (isConsecutive) {
                return potentialConsecutiveSlots;
            }
        }
        return Collections.emptyList();
    }

    private Room getAvailableRoom(int studentCapacity, List<Timeslot> selectedTimeslots, Map<Long, Set<Long>> roomBookedSlots, List<Room> allRooms) {
        // Prioritize CR rooms for single sections, then LT rooms
        List<Room> roomsToCheck = allRooms.stream()
            .filter(room -> room.getCapacity() >= studentCapacity)
            .sorted((r1, r2) -> {
                if (r1.getRoomType().equals("CR") && r2.getRoomType().equals("LT")) return -1;
                if (r1.getRoomType().equals("LT") && r2.getRoomType().equals("CR")) return 1;
                return 0; // Maintain natural order for same type
            })
            .collect(Collectors.toList());

        for (Room room : roomsToCheck) {
            boolean isRoomAvailableForAllSlots = true;
            for (Timeslot ts : selectedTimeslots) {
                if (roomBookedSlots.getOrDefault(room.getId(), Collections.emptySet()).contains(ts.getId())) {
                    isRoomAvailableForAllSlots = false;
                    break;
                }
            }
            if (isRoomAvailableForAllSlots) {
                return room;
            }
        }
        return null; // No available room found
    }

    private Room getAvailableLtRoom(int studentCapacity, List<Timeslot> selectedTimeslots, Map<Long, Set<Long>> roomBookedSlots, List<Room> allRooms) {
        // Specifically look for LT rooms for combined sections
        List<Room> ltRooms = allRooms.stream()
            .filter(room -> room.getRoomType().equals("LT") && room.getCapacity() >= studentCapacity)
            .collect(Collectors.toList());

        for (Room room : ltRooms) {
            boolean isRoomAvailableForAllSlots = true;
            for (Timeslot ts : selectedTimeslots) {
                if (roomBookedSlots.getOrDefault(room.getId(), Collections.emptySet()).contains(ts.getId())) {
                    isRoomAvailableForAllSlots = false;
                    break;
                }
            }
            if (isRoomAvailableForAllSlots) {
                return room;
            }
        }
        return null; // No available LT room found
    }

    private void assignLecture(String subjectName, Faculty faculty, Room room, List<Section> sections, List<Timeslot> timeslots,
                               List<Timetable> generatedTimetable, Map<Long, Set<Long>> facultyBookedSlots, Map<Long, Set<Long>> sectionBookedSlots,
                               Map<Long, Set<Long>> roomBookedSlots, Map<Long, Integer> facultyLectureLoad) {

        for (Timeslot ts : timeslots) {
            if (sections.size() > 1) {
                // Combined LT: create a single entry with all sections
                Timetable entry = new Timetable();
                entry.setSubjectName(subjectName);
                entry.setFaculty(faculty);
                entry.setRoom(room);
                entry.setSection(null); // set section to null for combined
                entry.setSections(new ArrayList<>(sections)); // setSections for combined
                entry.setTimeslot(ts);
                generatedTimetable.add(timetableRepository.save(entry));
                // Update booked slots and faculty load for all sections
                facultyBookedSlots.computeIfAbsent(faculty.getId(), k -> new HashSet<>()).add(ts.getId());
                roomBookedSlots.computeIfAbsent(room.getId(), k -> new HashSet<>()).add(ts.getId());
                facultyLectureLoad.merge(faculty.getId(), 1, Integer::sum);
                for (Section section : sections) {
                    sectionBookedSlots.computeIfAbsent(section.getId(), k -> new HashSet<>()).add(ts.getId());
                }
            } else {
                for (Section section : sections) {
                    Timetable entry = new Timetable();
                    entry.setSubjectName(subjectName);
                    entry.setFaculty(faculty);
                    entry.setRoom(room);
                    entry.setSection(section);
                    entry.setSections(Arrays.asList(section)); // set single section in sections list
                    entry.setTimeslot(ts);
                    generatedTimetable.add(timetableRepository.save(entry));
                    // Update booked slots and faculty load
                    facultyBookedSlots.computeIfAbsent(faculty.getId(), k -> new HashSet<>()).add(ts.getId());
                    sectionBookedSlots.computeIfAbsent(section.getId(), k -> new HashSet<>()).add(ts.getId());
                    roomBookedSlots.computeIfAbsent(room.getId(), k -> new HashSet<>()).add(ts.getId());
                    facultyLectureLoad.merge(faculty.getId(), 1, Integer::sum);
                }
            }
        }
    }

    public List<Timetable> getTimetable() {
        return timetableRepository.findAll();
    }

    private boolean tryAssignLectureWithFaculty(String subjectName, Section section, int duration, Faculty faculty,
        List<Timeslot> allTimeslots, List<Room> allRooms, List<Timetable> generatedTimetable, List<Map<String, Object>> skippedSlots,
        Map<Long, Set<Long>> facultyBookedSlots, Map<Long, Set<Long>> sectionBookedSlots, Map<Long, Set<Long>> roomBookedSlots, Map<Long, Integer> facultyLectureLoad,
        int subjectFrequency, Map<String, Set<String>> subjectSectionAssignedDays) {
        // This is a simplified version of tryAssignLecture for a single faculty
        String subjectSectionKey = subjectName + "|" + section.getId();
        String preferredDaysStr = faculty.getPreferredDays();
        List<String> facultyPreferredDays = (preferredDaysStr != null && !preferredDaysStr.isEmpty()) ?
            Arrays.asList(preferredDaysStr.split(",")).stream().map(String::trim).collect(Collectors.toList()) : Collections.emptyList();
        List<Timeslot> availableTimeslotsForFaculty = new ArrayList<>();
        List<Timeslot> otherTimeslotsForFaculty = new ArrayList<>();
        for (Timeslot ts : allTimeslots) {
            boolean isPreferredDay = facultyPreferredDays.isEmpty() || facultyPreferredDays.stream().anyMatch(day -> day.equalsIgnoreCase(ts.getDay()));
            boolean isFacultyAvailable = !facultyBookedSlots.getOrDefault(faculty.getId(), Collections.emptySet()).contains(ts.getId());
            boolean isSectionAvailable = !sectionBookedSlots.getOrDefault(section.getId(), Collections.emptySet()).contains(ts.getId());
            boolean isDayAlreadyUsed = subjectFrequency > 1 && subjectSectionAssignedDays.getOrDefault(subjectSectionKey, Collections.emptySet()).contains(ts.getDay());
            if (isFacultyAvailable && isSectionAvailable && !isDayAlreadyUsed) {
                if (isPreferredDay) {
                    availableTimeslotsForFaculty.add(ts);
                } else {
                    otherTimeslotsForFaculty.add(ts);
                }
            }
        }
        List<Timeslot> combinedAvailableTimeslots = new ArrayList<>(availableTimeslotsForFaculty);
        combinedAvailableTimeslots.addAll(otherTimeslotsForFaculty);
        List<Timeslot> consecutiveSlots = findConsecutiveTimeslots(combinedAvailableTimeslots, duration, faculty.getId(), section.getId(),
            facultyBookedSlots, sectionBookedSlots, allTimeslots);
        if (!consecutiveSlots.isEmpty()) {
            Room selectedRoom = getAvailableRoom(section.getStudentCount(), consecutiveSlots, roomBookedSlots, allRooms);
            if (selectedRoom != null) {
                assignLecture(subjectName, faculty, selectedRoom, Arrays.asList(section), consecutiveSlots, generatedTimetable,
                    facultyBookedSlots, sectionBookedSlots, roomBookedSlots, facultyLectureLoad);
                subjectSectionAssignedDays.computeIfAbsent(subjectSectionKey, k -> new HashSet<>()).add(consecutiveSlots.get(0).getDay());
                return true;
            }
        }
        return false;
    }

    private boolean tryAssignCombinedLectureWithFaculty_SubjectDayCheck(String subjectName, Section section1, Section section2, int duration, Faculty faculty,
        List<Timeslot> allTimeslots, List<Room> allRooms, List<Timetable> generatedTimetable, List<Map<String, Object>> skippedSlots,
        Map<Long, Set<Long>> facultyBookedSlots, Map<Long, Set<Long>> sectionBookedSlots, Map<Long, Set<Long>> roomBookedSlots, Map<Long, Integer> facultyLectureLoad,
        int subjectFrequency, Map<String, Set<String>> subjectSectionAssignedDays) {
        int combinedCapacityNeeded = section1.getStudentCount() + section2.getStudentCount();
        if (combinedCapacityNeeded > 180) return false;
        String section1Key = subjectName + "|" + section1.getId();
        String section2Key = subjectName + "|" + section2.getId();
        String preferredDaysStr = faculty.getPreferredDays();
        List<String> facultyPreferredDays = (preferredDaysStr != null && !preferredDaysStr.isEmpty()) ?
            Arrays.asList(preferredDaysStr.split(",")).stream().map(String::trim).collect(Collectors.toList()) : Collections.emptyList();
        List<Timeslot> availableTimeslotsForFaculty = new ArrayList<>();
        List<Timeslot> otherTimeslotsForFaculty = new ArrayList<>();
        for (Timeslot ts : allTimeslots) {
            boolean isPreferredDay = facultyPreferredDays.isEmpty() || facultyPreferredDays.stream().anyMatch(day -> day.equalsIgnoreCase(ts.getDay()));
            boolean isFacultyAvailable = !facultyBookedSlots.getOrDefault(faculty.getId(), Collections.emptySet()).contains(ts.getId());
            boolean isSection1Available = !sectionBookedSlots.getOrDefault(section1.getId(), Collections.emptySet()).contains(ts.getId());
            boolean isSection2Available = !sectionBookedSlots.getOrDefault(section2.getId(), Collections.emptySet()).contains(ts.getId());
            boolean isDayAlreadyUsedForS1 = subjectFrequency > 1 && subjectSectionAssignedDays.getOrDefault(section1Key, Collections.emptySet()).contains(ts.getDay());
            boolean isDayAlreadyUsedForS2 = subjectFrequency > 1 && subjectSectionAssignedDays.getOrDefault(section2Key, Collections.emptySet()).contains(ts.getDay());
            if (isFacultyAvailable && isSection1Available && isSection2Available && !isDayAlreadyUsedForS1 && !isDayAlreadyUsedForS2) {
                if (isPreferredDay) {
                    availableTimeslotsForFaculty.add(ts);
                } else {
                    otherTimeslotsForFaculty.add(ts);
                }
            }
        }
        List<Timeslot> combinedAvailableTimeslots = new ArrayList<>(availableTimeslotsForFaculty);
        combinedAvailableTimeslots.addAll(otherTimeslotsForFaculty);
        List<Timeslot> consecutiveSlots = findConsecutiveTimeslots(combinedAvailableTimeslots, duration, faculty.getId(), section1.getId(),
            facultyBookedSlots, sectionBookedSlots, allTimeslots);
        if (!consecutiveSlots.isEmpty()) {
            Room selectedRoom = getAvailableLtRoom(combinedCapacityNeeded, consecutiveSlots, roomBookedSlots, allRooms);
            if (selectedRoom == null) {
                // Fallback: try CRs if no LT available
                selectedRoom = getAvailableCrRoom(combinedCapacityNeeded, consecutiveSlots, roomBookedSlots, allRooms);
            }
            if (selectedRoom != null) {
                assignLecture(subjectName, faculty, selectedRoom, Arrays.asList(section1, section2), consecutiveSlots, generatedTimetable,
                    facultyBookedSlots, sectionBookedSlots, roomBookedSlots, facultyLectureLoad);
                subjectSectionAssignedDays.computeIfAbsent(section1Key, k -> new HashSet<>()).add(consecutiveSlots.get(0).getDay());
                subjectSectionAssignedDays.computeIfAbsent(section2Key, k -> new HashSet<>()).add(consecutiveSlots.get(0).getDay());
                return true;
            }
        }
        return false;
    }

    private boolean tryAssignLectureWithFaculty_SubjectDayCheck(String subjectName, Section section, int duration, Faculty faculty,
        List<Timeslot> allTimeslots, List<Room> allRooms, List<Timetable> generatedTimetable, List<Map<String, Object>> skippedSlots,
        Map<Long, Set<Long>> facultyBookedSlots, Map<Long, Set<Long>> sectionBookedSlots, Map<Long, Set<Long>> roomBookedSlots, Map<Long, Integer> facultyLectureLoad,
        int subjectFrequency, Map<String, Set<String>> subjectSectionAssignedDays) {
        String subjectSectionKey = subjectName + "|" + section.getId();
        String preferredDaysStr = faculty.getPreferredDays();
        List<String> facultyPreferredDays = (preferredDaysStr != null && !preferredDaysStr.isEmpty()) ?
            Arrays.asList(preferredDaysStr.split(",")).stream().map(String::trim).collect(Collectors.toList()) : Collections.emptyList();
        List<Timeslot> availableTimeslotsForFaculty = new ArrayList<>();
        List<Timeslot> otherTimeslotsForFaculty = new ArrayList<>();
        for (Timeslot ts : allTimeslots) {
            boolean isPreferredDay = facultyPreferredDays.isEmpty() || facultyPreferredDays.stream().anyMatch(day -> day.equalsIgnoreCase(ts.getDay()));
            boolean isFacultyAvailable = !facultyBookedSlots.getOrDefault(faculty.getId(), Collections.emptySet()).contains(ts.getId());
            boolean isSectionAvailable = !sectionBookedSlots.getOrDefault(section.getId(), Collections.emptySet()).contains(ts.getId());
            boolean isDayAlreadyUsed = subjectFrequency > 1 && subjectSectionAssignedDays.getOrDefault(subjectSectionKey, Collections.emptySet()).contains(ts.getDay());
            if (isFacultyAvailable && isSectionAvailable && !isDayAlreadyUsed) {
                if (isPreferredDay) {
                    availableTimeslotsForFaculty.add(ts);
                } else {
                    otherTimeslotsForFaculty.add(ts);
                }
            }
        }
        List<Timeslot> combinedAvailableTimeslots = new ArrayList<>(availableTimeslotsForFaculty);
        combinedAvailableTimeslots.addAll(otherTimeslotsForFaculty);
        List<Timeslot> consecutiveSlots = findConsecutiveTimeslots(combinedAvailableTimeslots, duration, faculty.getId(), section.getId(),
            facultyBookedSlots, sectionBookedSlots, allTimeslots);
        if (!consecutiveSlots.isEmpty()) {
            Room selectedRoom = getAvailableRoom(section.getStudentCount(), consecutiveSlots, roomBookedSlots, allRooms);
            if (selectedRoom != null) {
                assignLecture(subjectName, faculty, selectedRoom, Arrays.asList(section), consecutiveSlots, generatedTimetable,
                    facultyBookedSlots, sectionBookedSlots, roomBookedSlots, facultyLectureLoad);
                subjectSectionAssignedDays.computeIfAbsent(subjectSectionKey, k -> new HashSet<>()).add(consecutiveSlots.get(0).getDay());
                return true;
            }
        }
        return false;
    }

    private Room getAvailableCrRoom(int studentCapacity, List<Timeslot> selectedTimeslots, Map<Long, Set<Long>> roomBookedSlots, List<Room> allRooms) {
        List<Room> crRooms = allRooms.stream()
            .filter(room -> room.getRoomType().equals("CR") && room.getCapacity() >= studentCapacity)
            .collect(Collectors.toList());
        for (Room room : crRooms) {
            boolean isRoomAvailableForAllSlots = true;
            for (Timeslot ts : selectedTimeslots) {
                if (roomBookedSlots.getOrDefault(room.getId(), Collections.emptySet()).contains(ts.getId())) {
                    isRoomAvailableForAllSlots = false;
                    break;
                }
            }
            if (isRoomAvailableForAllSlots) {
                return room;
            }
        }
        return null;
    }
} 