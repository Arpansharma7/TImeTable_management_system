Timetable Generator
A full-stack application for generating academic timetables based on user-defined constraints such as faculty availability, room capacity, and section requirements.
Features
Automated Timetable Generation: Generates conflict-free timetables for multiple sections, subjects, and faculties.
Constraint Handling: Considers faculty preferences, room capacities, lecture durations, and more.
Web Interface: Simple frontend for input and visualization.
Extensible Backend: Built with Java Spring Boot for easy customization.

Getting Started --

Prerequisites
Java 11 or higher
Maven

How It Works
Input: User provides subjects, sections, faculties, and constraints.
Processing: The backend algorithm schedules lectures, ensuring no conflicts for faculty, rooms, or sections, and respecting all constraints.
Output: Returns a generated timetable and a list of any unscheduled lectures with reasons.

here is the flow diagram for the whole scheduling process-
![image](https://github.com/user-attachments/assets/2aa4e5db-aaf8-4c5a-a439-e7331a3a3ef9)
