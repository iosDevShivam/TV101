package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public class IcsTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static long at(String iso) {
        return ZonedDateTime.parse(iso).toInstant().toEpochMilli();
    }

    private static String calendar(String... events) {
        StringBuilder s = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
        for (String e : events) s.append("BEGIN:VEVENT\r\n").append(e).append("END:VEVENT\r\n");
        return s.append("END:VCALENDAR\r\n").toString();
    }

    @Test
    public void singleEventInUtcLandsInLocalTime() {
        String ics = calendar("UID:a\r\nDTSTART:20261108T130000Z\r\nDTEND:20261108T140000Z\r\nSUMMARY:Dinner with family\r\n");
        List<Ics.Event> events = Ics.events(ics, at("2026-11-08T12:00+05:30[Asia/Kolkata]"),
                at("2026-11-10T00:00+05:30[Asia/Kolkata]"), IST);
        assertEquals(1, events.size());
        assertEquals(at("2026-11-08T18:30+05:30[Asia/Kolkata]"), events.get(0).start);
        assertEquals("Dinner with family", events.get(0).title);
    }

    @Test
    public void weeklyByDaySkipsExdateAndMovedOccurrence() {
        String ics = calendar(
                "UID:standup\r\nDTSTART;TZID=Asia/Kolkata:20260105T100000\r\nDTEND;TZID=Asia/Kolkata:20260105T101500\r\n"
                        + "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR\r\nEXDATE;TZID=Asia/Kolkata:20261111T100000\r\nSUMMARY:Stand\r\n  up\r\n",
                "UID:standup\r\nRECURRENCE-ID;TZID=Asia/Kolkata:20261109T100000\r\n"
                        + "DTSTART;TZID=Asia/Kolkata:20261109T113000\r\nDTEND;TZID=Asia/Kolkata:20261109T114500\r\nSUMMARY:Stand up (moved)\r\n");
        List<Ics.Event> events = Ics.events(ics, at("2026-11-08T00:00+05:30[Asia/Kolkata]"),
                at("2026-11-14T00:00+05:30[Asia/Kolkata]"), IST);
        // Monday moved to 11:30, Wednesday excluded, Friday as usual.
        assertEquals(2, events.size());
        assertEquals(at("2026-11-09T11:30+05:30[Asia/Kolkata]"), events.get(0).start);
        assertEquals("Stand up (moved)", events.get(0).title);
        assertEquals(at("2026-11-13T10:00+05:30[Asia/Kolkata]"), events.get(1).start);
        assertEquals("Stand up", events.get(1).title);
    }

    @Test
    public void countAndAllDay() {
        String ics = calendar(
                "UID:c\r\nDTSTART;TZID=Asia/Kolkata:20261101T090000\r\nRRULE:FREQ=DAILY;COUNT=5\r\nSUMMARY:Pills\r\n",
                "UID:d\r\nDTSTART;VALUE=DATE:20261108\r\nDTEND;VALUE=DATE:20261109\r\nSUMMARY:Birthday\r\n",
                "UID:e\r\nDTSTART:20261108T050000Z\r\nSTATUS:CANCELLED\r\nSUMMARY:Gone\r\n");
        List<Ics.Event> events = Ics.events(ics, at("2026-11-04T00:00+05:30[Asia/Kolkata]"),
                at("2026-11-09T00:00+05:30[Asia/Kolkata]"), IST);
        // Nov 4 and 5 are the last two of the five; then the birthday.
        assertEquals(3, events.size());
        assertEquals("Pills", events.get(1).title);
        assertEquals(true, events.get(2).allDay);
    }
}
