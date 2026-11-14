package cn.local.micompanion;

import java.time.Instant;
import java.time.ZoneId;

public final class WeatherRefreshScheduleTest {
 public static void main(String[] args) {
  expect("2026-11-13T23:59:00+08:00", "Asia/Shanghai", "2026-11-14T00:00:00+08:00");
  expect("2026-11-13T00:00:00+08:00", "Asia/Shanghai", "2026-11-13T02:00:00+08:00");
  expect("2026-11-13T01:30:00+08:00", "Asia/Shanghai", "2026-11-13T02:00:00+08:00");
  expect("2026-01-31T23:59:00+08:00", "Asia/Shanghai", "2026-02-01T00:00:00+08:00");
  expect("2026-12-31T23:59:59+08:00", "Asia/Shanghai", "2027-01-01T00:00:00+08:00");
  expect("2026-11-13T01:59:59.999+08:00", "Asia/Shanghai", "2026-11-13T02:00:00+08:00");
  // Same instant, a newly selected phone time zone has a different next wall-clock slot.
  expect("2026-11-13T00:30:00Z", "Asia/Shanghai", "2026-11-13T02:00:00Z");
  expect("2026-11-13T00:30:00Z", "Asia/Kathmandu", "2026-11-13T02:15:00Z");
  // Berlin's missing 02:00 is skipped, not shifted to 03:00.
  expect("2026-03-29T01:30:00+01:00", "Europe/Berlin", "2026-03-29T04:00:00+02:00");
  // The repeated 02:00 is used only at its earlier instant.
  expect("2026-10-25T01:30:00+02:00", "Europe/Berlin", "2026-10-25T02:00:00+02:00");
  expect("2026-10-25T02:00:00+02:00", "Europe/Berlin", "2026-10-25T04:00:00+01:00");
  expect("2026-10-25T02:30:00+01:00", "Europe/Berlin", "2026-10-25T04:00:00+01:00");
  System.out.println("WeatherRefreshScheduleTest passed");
 }
 static void expect(String now, String zone, String expected) {
  long actual=WeatherRefreshSchedule.next(java.time.OffsetDateTime.parse(now).toInstant().toEpochMilli(),ZoneId.of(zone));
  long wanted=java.time.OffsetDateTime.parse(expected).toInstant().toEpochMilli();
  if(actual!=wanted) throw new AssertionError(now+" / "+zone+": expected "+expected+", got "+Instant.ofEpochMilli(actual));
 }
}
