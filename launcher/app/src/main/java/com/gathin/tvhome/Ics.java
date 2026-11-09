package com.gathin.tvhome;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The events of an iCalendar file (Google Calendar's "secret address in iCal format") that fall in a window,
 * repeating events included. Covers what calendars export in practice: DTSTART with TZID, UTC or a date,
 * DTEND or DURATION, RRULE with FREQ, INTERVAL, COUNT, UNTIL and plain BYDAY (MO,WE), EXDATE, moved or
 * cancelled single occurrences (RECURRENCE-ID) and cancelled events. Anything else repeats as FREQ alone.
 */
final class Ics {
    /** One occurrence: start and end in epoch milliseconds. */
    static final class Event {
        final long start, end;
        final boolean allDay;
        final String title;

        Event(long start, long end, boolean allDay, String title) {
            this.start = start;
            this.end = end;
            this.allDay = allDay;
            this.title = title;
        }
    }

    private static final class Raw {
        final Map<String, String> props = new HashMap<>();
        final Map<String, String> params = new HashMap<>();
        final List<String[]> exdates = new ArrayList<>();
    }

    private static final int MAX_STEPS = 20_000;

    private Ics() {
    }

    static List<Event> events(String text, long from, long to, ZoneId local) {
        List<Raw> raws = new ArrayList<>();
        Raw cur = null;
        for (String line : unfold(text)) {
            if (line.equals("BEGIN:VEVENT")) {
                cur = new Raw();
            } else if (line.equals("END:VEVENT")) {
                if (cur != null) raws.add(cur);
                cur = null;
            } else if (cur != null) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String[] head = line.substring(0, colon).split(";");
                String name = head[0].toUpperCase();
                String value = line.substring(colon + 1);
                String tzid = null;
                for (int i = 1; i < head.length; i++) {
                    if (head[i].toUpperCase().startsWith("TZID=")) tzid = head[i].substring(5).replace("\"", "");
                }
                if (name.equals("EXDATE")) {
                    for (String v : value.split(",")) cur.exdates.add(new String[]{v, tzid});
                } else if (!cur.props.containsKey(name)) {
                    cur.props.put(name, value);
                    if (tzid != null) cur.params.put(name, tzid);
                }
            }
        }
        // Occurrences moved or cancelled on their own: the series skips them, the override stands for itself.
        Map<String, Set<Long>> overridden = new HashMap<>();
        for (Raw r : raws) {
            String rid = r.props.get("RECURRENCE-ID");
            if (rid == null) continue;
            Long at = parse(rid, r.params.get("RECURRENCE-ID"), local);
            if (at != null) overridden.computeIfAbsent(r.props.getOrDefault("UID", ""), k -> new HashSet<>()).add(at);
        }
        List<Event> out = new ArrayList<>();
        for (Raw r : raws) {
            if ("CANCELLED".equalsIgnoreCase(r.props.get("STATUS"))) continue;
            String dtstart = r.props.get("DTSTART");
            if (dtstart == null) continue;
            boolean allDay = !dtstart.contains("T");
            ZonedDateTime start = zoned(dtstart, r.params.get("DTSTART"), local);
            if (start == null) continue;
            Duration length = length(r, start, local, allDay);
            String title = unescape(r.props.getOrDefault("SUMMARY", "")).trim();
            if (title.isEmpty()) title = "Event";
            Set<Long> skip = new HashSet<>();
            for (String[] ex : r.exdates) {
                Long at = parse(ex[0], ex[1], local);
                if (at != null) skip.add(at);
            }
            Set<Long> moved = r.props.containsKey("RECURRENCE-ID") ? null
                    : overridden.get(r.props.getOrDefault("UID", ""));
            String rule = r.props.get("RRULE");
            if (rule == null || r.props.containsKey("RECURRENCE-ID")) {
                add(out, start, length, allDay, title, from, to);
                continue;
            }
            for (ZonedDateTime at : repeat(start, rule, to, local)) {
                long ms = at.toInstant().toEpochMilli();
                if (skip.contains(ms) || (moved != null && moved.contains(ms))) continue;
                add(out, at, length, allDay, title, from, to);
            }
        }
        out.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    private static void add(List<Event> out, ZonedDateTime start, Duration length, boolean allDay, String title,
                            long from, long to) {
        long s = start.toInstant().toEpochMilli();
        long e = s + length.toMillis();
        if (e > from && s < to) out.add(new Event(s, e, allDay, title));
    }

    /** The starts of a repeating event, up to the end of the window. */
    private static List<ZonedDateTime> repeat(ZonedDateTime start, String rule, long to, ZoneId local) {
        Map<String, String> r = new HashMap<>();
        for (String part : rule.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) r.put(part.substring(0, eq).toUpperCase(), part.substring(eq + 1));
        }
        String freq = r.getOrDefault("FREQ", "");
        int interval = Math.max(1, parseInt(r.get("INTERVAL"), 1));
        int count = parseInt(r.get("COUNT"), Integer.MAX_VALUE);
        Long until = r.containsKey("UNTIL") ? parse(r.get("UNTIL"), null, local) : null;
        long limit = until == null ? to : Math.min(to, until + 1);
        List<DayOfWeek> days = new ArrayList<>();
        if (r.containsKey("BYDAY") && freq.equals("WEEKLY")) {
            for (String d : r.get("BYDAY").split(",")) {
                DayOfWeek day = day(d.trim());
                if (day != null) days.add(day);
            }
        }
        List<ZonedDateTime> starts = new ArrayList<>();
        int made = 0;
        for (int step = 0; step < MAX_STEPS && made < count; step++) {
            if (freq.equals("WEEKLY") && !days.isEmpty()) {
                // The week of this step, starting on the event's own weekday; each listed day in it.
                ZonedDateTime week = start.plusWeeks((long) step * interval);
                LocalDate monday = week.toLocalDate().minusDays(week.getDayOfWeek().getValue() - 1);
                boolean past = false;
                for (DayOfWeek d : sorted(days)) {
                    ZonedDateTime at = ZonedDateTime.of(monday.plusDays(d.getValue() - 1), start.toLocalTime(), start.getZone());
                    if (at.isBefore(start)) continue;
                    if (at.toInstant().toEpochMilli() >= limit || made >= count) {
                        past = true;
                        break;
                    }
                    starts.add(at);
                    made++;
                }
                if (past) break;
                continue;
            }
            ZonedDateTime at;
            switch (freq) {
                case "DAILY": at = start.plusDays((long) step * interval); break;
                case "WEEKLY": at = start.plusWeeks((long) step * interval); break;
                case "MONTHLY": at = start.plusMonths((long) step * interval); break;
                case "YEARLY": at = start.plusYears((long) step * interval); break;
                default: return starts.isEmpty() ? List.of(start) : starts;
            }
            if (at.toInstant().toEpochMilli() >= limit) break;
            starts.add(at);
            made++;
        }
        // Only the ones near the window matter; the rest were needed for COUNT.
        return starts;
    }

    private static List<DayOfWeek> sorted(List<DayOfWeek> days) {
        List<DayOfWeek> copy = new ArrayList<>(days);
        copy.sort(null);
        return copy;
    }

    private static DayOfWeek day(String code) {
        String c = code.replaceAll("^[+-]?\\d+", "");
        switch (c) {
            case "MO": return DayOfWeek.MONDAY;
            case "TU": return DayOfWeek.TUESDAY;
            case "WE": return DayOfWeek.WEDNESDAY;
            case "TH": return DayOfWeek.THURSDAY;
            case "FR": return DayOfWeek.FRIDAY;
            case "SA": return DayOfWeek.SATURDAY;
            case "SU": return DayOfWeek.SUNDAY;
            default: return null;
        }
    }

    private static Duration length(Raw r, ZonedDateTime start, ZoneId local, boolean allDay) {
        String end = r.props.get("DTEND");
        if (end != null) {
            ZonedDateTime e = zoned(end, r.params.get("DTEND"), local);
            if (e != null && e.isAfter(start)) return Duration.between(start, e);
        }
        String duration = r.props.get("DURATION");
        if (duration != null) {
            try {
                // Duration has no weeks: "P2W" becomes "P14D".
                java.util.regex.Matcher weeks = java.util.regex.Pattern.compile("P(\\d+)W").matcher(duration);
                if (weeks.matches()) return Duration.ofDays(7L * Integer.parseInt(weeks.group(1)));
                return Duration.parse(duration);
            } catch (RuntimeException ignored) {
            }
        }
        return allDay ? Duration.ofDays(1) : Duration.ofHours(1);
    }

    private static Long parse(String value, String tzid, ZoneId local) {
        ZonedDateTime z = zoned(value, tzid, local);
        return z == null ? null : z.toInstant().toEpochMilli();
    }

    private static ZonedDateTime zoned(String value, String tzid, ZoneId local) {
        try {
            String v = value.trim();
            if (!v.contains("T")) {
                return LocalDate.parse(v.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(local);
            }
            LocalDateTime t = LocalDateTime.parse(v.substring(0, 15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            if (v.endsWith("Z")) return t.atZone(ZoneOffset.UTC).withZoneSameInstant(local);
            ZoneId zone = local;
            if (tzid != null) {
                try {
                    zone = ZoneId.of(tzid);
                } catch (RuntimeException ignored) {
                }
            }
            return t.atZone(zone);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> unfold(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = null;
        for (String raw : text.split("\r?\n")) {
            if (cur != null && (raw.startsWith(" ") || raw.startsWith("\t"))) {
                cur.append(raw, 1, raw.length());
                continue;
            }
            if (cur != null) lines.add(cur.toString());
            cur = new StringBuilder(raw);
        }
        if (cur != null) lines.add(cur.toString());
        return lines;
    }

    private static String unescape(String s) {
        return s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    /** Whole days between two dates in the TV's zone; 0 today, 1 tomorrow. */
    static long daysFrom(long now, long at, ZoneId local) {
        LocalDate a = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), local).toLocalDate();
        LocalDate b = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), local).toLocalDate();
        return ChronoUnit.DAYS.between(a, b);
    }
}
