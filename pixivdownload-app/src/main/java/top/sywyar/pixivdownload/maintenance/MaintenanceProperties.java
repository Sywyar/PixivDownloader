package top.sywyar.pixivdownload.maintenance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Optional;
import java.util.regex.Pattern;

@Data
@Configuration
@ConfigurationProperties(prefix = "maintenance")
public class MaintenanceProperties {

    public static final String DEFAULT_TIME = "10:00";
    private static final Pattern TIME_PATTERN = Pattern.compile("^(?:[01]?\\d|2[0-3]):[0-5]?\\d$");

    private volatile boolean enabled = true;
    private volatile DaySchedule monday = new DaySchedule(true, DEFAULT_TIME);
    private volatile DaySchedule tuesday = new DaySchedule(false, DEFAULT_TIME);
    private volatile DaySchedule wednesday = new DaySchedule(false, DEFAULT_TIME);
    private volatile DaySchedule thursday = new DaySchedule(false, DEFAULT_TIME);
    private volatile DaySchedule friday = new DaySchedule(false, DEFAULT_TIME);
    private volatile DaySchedule saturday = new DaySchedule(false, DEFAULT_TIME);
    private volatile DaySchedule sunday = new DaySchedule(false, DEFAULT_TIME);

    public Optional<LocalTime> scheduledTime(DayOfWeek dayOfWeek) {
        DaySchedule schedule = scheduleFor(dayOfWeek);
        if (!schedule.isEnabled()) {
            return Optional.empty();
        }
        return parseTime(schedule.getTime());
    }

    public DaySchedule scheduleFor(DayOfWeek dayOfWeek) {
        DaySchedule schedule = switch (dayOfWeek) {
            case MONDAY -> monday;
            case TUESDAY -> tuesday;
            case WEDNESDAY -> wednesday;
            case THURSDAY -> thursday;
            case FRIDAY -> friday;
            case SATURDAY -> saturday;
            case SUNDAY -> sunday;
        };
        return schedule == null ? defaultSchedule(dayOfWeek) : schedule;
    }

    public DaySchedule mutableScheduleFor(DayOfWeek dayOfWeek) {
        DaySchedule schedule = scheduleFor(dayOfWeek);
        switch (dayOfWeek) {
            case MONDAY -> monday = schedule;
            case TUESDAY -> tuesday = schedule;
            case WEDNESDAY -> wednesday = schedule;
            case THURSDAY -> thursday = schedule;
            case FRIDAY -> friday = schedule;
            case SATURDAY -> saturday = schedule;
            case SUNDAY -> sunday = schedule;
        }
        return schedule;
    }

    public static boolean isValidTime(String value) {
        return parseTime(value).isPresent();
    }

    public static Optional<LocalTime> parseTime(String value) {
        if (value == null || !TIME_PATTERN.matcher(value.trim()).matches()) {
            return Optional.empty();
        }
        String[] parts = value.trim().split(":");
        return Optional.of(LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
    }

    private static DaySchedule defaultSchedule(DayOfWeek dayOfWeek) {
        return new DaySchedule(dayOfWeek == DayOfWeek.MONDAY, DEFAULT_TIME);
    }

    @Data
    public static class DaySchedule {

        private volatile boolean enabled;
        private volatile String time = DEFAULT_TIME;

        public DaySchedule() {
        }

        public DaySchedule(boolean enabled, String time) {
            this.enabled = enabled;
            this.time = time;
        }
    }
}
