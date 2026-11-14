package cn.local.micompanion;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.List;

/** Phone-local 00:00, 02:00, ... 22:00 slots, independent of app start time. */
public final class WeatherRefreshSchedule {
 private WeatherRefreshSchedule() {}

 /** Returns an epoch-millisecond alarm strictly after now. Gaps skip; overlaps use only the earlier instant. */
 public static long next(long now, ZoneId zone) {
  LocalDate date=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
  ZoneRules rules=zone.getRules();
  for (;; date=date.plusDays(1)) {
   long earliest=Long.MAX_VALUE;
   for (int hour=0;hour<24;hour+=2) {
    LocalDateTime slot=date.atTime(hour,0);
    List<ZoneOffset> offsets=rules.getValidOffsets(slot);
    if (offsets.isEmpty()) continue;
    // ZoneRules lists the pre-transition offset first: the earlier occurrence in an overlap.
    long candidate=slot.toInstant(offsets.get(0)).toEpochMilli();
    if (candidate>now && candidate<earliest) earliest=candidate;
   }
   if (earliest!=Long.MAX_VALUE) return earliest;
  }
 }
}
