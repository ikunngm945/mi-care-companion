package cn.local.micompanion;
/** UsageEvents types: 15 interactive,16 noninteractive,17 locked,18 unlocked. */
public final class UsageTimeline {
 private final long start,end;
 private long screenStart=-1, useStart=-1, screen=0, use=0;
 public UsageTimeline(long start,long end) { this.start=start; this.end=end; }
 public void event(long time,int type) {
  time=Math.min(time,end);
  if(type==15 && screenStart<0) screenStart=Math.max(start,time);
  if(type==18 && screenStart>=0 && useStart<0) useStart=Math.max(start,time);
  if(type==17 || type==16) {
   if(useStart>=0) {use+=Math.max(0,time-useStart);useStart=-1;}
  }
  if(type==16 && screenStart>=0) {screen+=Math.max(0,time-screenStart);screenStart=-1;}
 }
 public long[] finish() {
  return new long[]{screen+(screenStart<0?0:Math.max(0,end-screenStart)),use+(useStart<0?0:Math.max(0,end-useStart))};
 }
}
