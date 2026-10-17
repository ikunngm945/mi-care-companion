package cn.local.micompanion;
public class UsageTimelineTest {
 public static void main(String[] args) {
  UsageTimeline t = new UsageTimeline(1000,2000);
  t.event(1100,15); t.event(1200,18); t.event(1500,17); t.event(1600,16);
  long[] x=t.finish();
  if(x[0]!=500 || x[1]!=300) throw new AssertionError("screen and unlocked intervals");
  t=new UsageTimeline(1000,2000); t.event(1400,16); x=t.finish();
  if(x[0]!=0 || x[1]!=0) throw new AssertionError("unknown initial state must not invent time");
  t=new UsageTimeline(1000,1800); t.event(1200,15); t.event(1400,15); t.event(1500,18);x=t.finish();
  if(x[0]!=600 || x[1]!=300) throw new AssertionError("duplicates and open interval");
  t=new UsageTimeline(1000,2000); t.event(500,15); t.event(800,18); t.event(2500,16); x=t.finish();
  if(x[0]!=1000 || x[1]!=1000) throw new AssertionError("events after interval end must be clipped");
  System.out.println("UsageTimeline: 4 checks passed");
 }
}
