package com.timetable;

import com.timetable.model.Faculty;
import com.timetable.model.Room;
import com.timetable.model.Section;
import com.timetable.model.Timeslot;
import com.timetable.repository.FacultyRepository;
import com.timetable.repository.RoomRepository;
import com.timetable.repository.SectionRepository;
import com.timetable.repository.TimeslotRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.LocalTime;
import java.util.Arrays;

@SpringBootApplication
public class TimetableBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(TimetableBackendApplication.class, args);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:5500")
                        .allowedMethods("GET", "POST", "PUT", "DELETE")
                        .allowedHeaders("*");
            }
        };
    }

    @Bean
    public CommandLineRunner demoData(
            FacultyRepository facultyRepository,
            RoomRepository roomRepository,
            SectionRepository sectionRepository,
            TimeslotRepository timeslotRepository)
    {
        return args -> {
        };
    }
} 