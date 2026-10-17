package cn.local.micompanion;
import java.util.*;
import org.json.*;
public final class ScreenCollectionTest {
 private static int checks;
 private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  final long start=100_000_000L,end=start+1000;
  final long[] query=new long[2];
  UsageCollector.Reader reader=(a,b)->{
   query[0]=a;query[1]=b;
   return Arrays.asList(new UsageCollector.Event(start-500,15,null),new UsageCollector.Event(start-400,18,null),
    new UsageCollector.Event(start-300,1,"app"),new UsageCollector.Event(end+500,16,null)).iterator();
  };
  JSONObject result=UsageCollector.collect(reader,start,end,end+600);
  JSONObject f=result.getJSONObject("fields");
  check(query[0]==start-86400000L&&query[1]==end,"query previous day only through exact health end");
  check(result.getString("status").equals("ok"),"prior state is valid interval evidence");
  check(f.getLong("screen_on_ms")==1000&&f.getLong("unlocked_use_ms")==1000,"screen clipped at both cycle boundaries");
  check(f.getJSONArray("top_apps").getJSONObject(0).getLong("foreground_ms")==1000,"foreground clipped at both cycle boundaries");
  check(f.getString("interval_end").equals(UsageCollector.iso(end)),"source end never changed to now");
  check(result.getString("source_updated_at").equals(UsageCollector.iso(end)),"source timestamp describes frozen end");
  for(long[] interval:new long[][]{{0,end},{end,start},{start,end+601},{start,start+604800001L}}){
   JSONObject invalid=UsageCollector.collect((a,b)->{throw new AssertionError("invalid bounds queried source");},interval[0],interval[1],end+600);
   check(invalid.getString("status").equals("error")&&invalid.getJSONObject("fields").length()==0,"invalid interval explicit error without source query");
  }
  JSONObject tooLong=UsageCollector.collect((a,b)->{throw new AssertionError("overlong interval queried source");},start,start+604800001L,start+604800001L);
  check(tooLong.getString("status").equals("error"),"maximum seven day span validated independently of now");
  JSONObject week=UsageCollector.collect((a,b)->Collections.<UsageCollector.Event>emptyList().iterator(),start,start+604800000L,start+604800000L);
  check(week.getString("status").equals("missing"),"exact seven day interval accepted");
  JSONObject limit=UsageCollector.collect((a,b)->new Iterator<UsageCollector.Event>(){
   int i;public boolean hasNext(){return i<100001;}public UsageCollector.Event next(){i++;return new UsageCollector.Event(start,15,null);}
  },start,end,end);
  check(limit.getString("status").equals("error")&&limit.getJSONObject("fields").length()==0,"event overflow never exports truncated totals");
  JSONObject exact=UsageCollector.collect((a,b)->new Iterator<UsageCollector.Event>(){
   int i;public boolean hasNext(){return i<100000;}public UsageCollector.Event next(){i++;return new UsageCollector.Event(start,15,null);}
  },start,end,end);
  check(exact.getString("status").equals("ok"),"event cap accepts exactly 100000");
  List<UsageCollector.Event> apps=new ArrayList<>();
  for(int i=0;i<12;i++){apps.add(new UsageCollector.Event(start+i*50,1,"app"+i));apps.add(new UsageCollector.Event(start+i*50+i+1,2,"app"+i));}
  JSONObject top=UsageCollector.collect((a,b)->apps.iterator(),start,end,end).getJSONObject("fields");
  check(top.getJSONArray("top_apps").length()==10&&top.getJSONArray("top_apps").getJSONObject(0).getString("package").equals("app11"),"app top10 unchanged and duration sorted");
  JSONObject empty=UsageCollector.collect((a,b)->Collections.<UsageCollector.Event>emptyList().iterator(),start,end,end);
  check(empty.getString("status").equals("missing"),"empty events do not invent usage");
  System.out.println("ScreenCollection: "+checks+" checks passed");
 }
}
