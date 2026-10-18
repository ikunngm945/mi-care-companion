package cn.local.micompanion;
import java.util.*;
import org.json.*;
/** Bounded deterministic projection of measured records; never writes to the source DB. */
final class HealthCycle {
 private static final int LIMIT=12000, MAX_STAGES=512;
 private static final long DAY=86400;
 private static JSONObject unavailable(String state)throws JSONException{return new JSONObject().put("status",state).put("reason","未找到两次有效主睡眠或周期数据不可用；不以自然日替代");}
 private static long integer(JSONObject v,String k)throws Exception{
  Object o=v.get(k);if(!(o instanceof Number))throw new IllegalArgumentException("numeric field");
  double d=((Number)o).doubleValue();long n=((Number)o).longValue();if(!Double.isFinite(d)||d!=n)throw new IllegalArgumentException("integer field");return n;
 }
 private static JSONObject parse(JSONObject row)throws Exception{
  String raw=row.getString("value");if(raw.length()>1024*1024)throw new IllegalArgumentException("record size");
  RouteResolver.validateJsonObject(raw);return new JSONObject(raw);
 }
 private static JSONArray query(HealthSource.Reader db,String table,String where,long a,long b,int limit)throws Exception{
  if(db==null)throw new IllegalArgumentException("missing reader");
  // table and where are internal constants, not source strings. Keep one sentinel row to detect overflow.
  String end="sleep_segment".equals(table)?"endTime":"time";
  JSONArray rows=db.query("SELECT value,time,0 AS updateTime,"+("sport_report".equals(table)?"time":"endTime")+",sid,key FROM "+table+" WHERE isDeleted=0 AND "+where+" AND "+end+">? AND "+end+"<=? ORDER BY "+end+" ASC LIMIT "+(limit+1),new String[]{Long.toString(a),Long.toString(b)});
  if(rows==null||rows.length()>limit)throw new IllegalArgumentException("record count");return rows;
 }
 static JSONObject collect(HealthSource.Reader db,long now,TimeZone zone)throws Exception{
  try{
   long until=now/1000;
   // Native sleep reads include isComplete=0; use payload bounds, not this source flag, to validate ended sleep.
   JSONArray rows=query(db,"sleep_segment","key='sleep'",until-14*DAY,until,64);
   TreeMap<Long,JSONObject> sleeps=new TreeMap<>();
   for(int i=0;i<rows.length();i++){
    JSONObject v=parse(rows.getJSONObject(i));long a=integer(v,"bedtime"),b=integer(v,"wake_up_time"),duration=integer(v,"duration");
    if(a<=0||b<=a||b>until||b<=until-14*DAY||duration<0||duration>(b-a)/60+1||b-a>7*DAY)throw new IllegalArgumentException("sleep bounds");
    if(sleeps.containsKey(b)){
     JSONObject old=sleeps.get(b);if(integer(old,"bedtime")!=a||integer(old,"duration")!=duration||!old.optJSONArray("items").toString().equals(v.optJSONArray("items").toString()))throw new IllegalArgumentException("conflicting sleeps");
    }else sleeps.put(b,v);
   }
   ArrayList<Long> main=new ArrayList<>();for(Map.Entry<Long,JSONObject> e:sleeps.entrySet())if(integer(e.getValue(),"duration")>=180)main.add(e.getKey());
   if(main.size()<2)return unavailable("missing");
   long start=main.get(main.size()-2),end=until;
   if(end<=start||end-start>7*DAY)return unavailable("missing");
   JSONObject out=new JSONObject().put("status","ok").put("start_ms",start*1000).put("end_ms",now).put("timezone",zone.getID());
   List<long[]> hr=points(db,"hr_record","key IN ('heart_rate','single_heart_rate')","bpm",start,end,1,Integer.MAX_VALUE);
   List<long[]> sp=points(db,"spo2_record","key IN ('spo2','single_spo2')","spo2",start,end,1,100);
   List<long[]> st=points(db,"stress_record","key IN ('stress','single_stress')","stress",start,end,1,100);
   out.put("heart_rate",hr==null?JSONObject.NULL:bins(hr,start,end,zone)).put("spo2",sp==null?JSONObject.NULL:bins(sp,start,end,zone)).put("stress",st==null?JSONObject.NULL:bins(st,start,end,zone));
   JSONObject activity=new JSONObject().put("steps",steps(db,start,end,zone)).put("calories_kcal",calories(db,start,end,zone)).put("mh_minutes",intensity(db,start,end)).put("sport_count",sports(db,start,end));
   out.put("activity",activity);JSONArray selected=new JSONArray();int stageCount=0;
   for(Map.Entry<Long,JSONObject> e:sleeps.tailMap(start,false).entrySet()){
    if(e.getKey().longValue()!=main.get(main.size()-1).longValue())continue;
    JSONObject v=e.getValue();long a=integer(v,"bedtime"),b=e.getKey();if(a<start)throw new IllegalArgumentException("overlapping sleeps");
    JSONArray items=v.optJSONArray("items"),stages=new JSONArray();if(items!=null){stageCount+=items.length();if(stageCount>MAX_STAGES)throw new IllegalArgumentException("stage count");
     long prev=a;
     for(int j=0;j<items.length();j++){
      JSONObject item=items.getJSONObject(j);long x=integer(item,item.has("start_time")?"start_time":"start"),y=integer(item,item.has("end_time")?"end_time":"end"),state=integer(item,"state");
      if(x<prev||y<=x||x<a||y>b||state<Integer.MIN_VALUE||state>Integer.MAX_VALUE)throw new IllegalArgumentException("stage bounds");prev=y;
      stages.put(new JSONObject().put("start_ms",x*1000).put("end_ms",y*1000).put("state",state).put("heart_rate",range(hr,x,y)).put("spo2",range(sp,x,y)));
     }
    }
    selected.put(new JSONObject().put("start_ms",a*1000).put("end_ms",b*1000).put("stages",stages));
   }
   out.put("sleep",selected).put("measurement_note","区间实测散点按本地半小时分桶，首尾裁切；样本及睡眠阶段使用(start,end]，不继承缺测值；活动为可读取明细总数，源缺失可能低估");
   if(out.toString().getBytes("UTF-8").length>160*1024)return unavailable("error");return out;
  }catch(Exception ex){return unavailable("error");}
 }
 private static List<long[]> points(HealthSource.Reader db,String table,String where,String key,long start,long end,int min,int max){
  try{
   JSONArray rows=query(db,table,where,start,end,LIMIT);TreeMap<String,long[]> unique=new TreeMap<>();
   for(int i=0;i<rows.length();i++){
    JSONObject row=rows.getJSONObject(i),v=parse(row);long at=integer(v,"time"),n=integer(v,key);
    if(at!=integer(row,"time"))throw new IllegalArgumentException("time mismatch");if(at<=start||at>end)continue;
    if(n<min||n>max)continue;unique.put(at+":"+n,new long[]{at,n});
   }
   ArrayList<long[]> result=new ArrayList<>(unique.values());Collections.sort(result,(a,b)->Long.compare(a[0],b[0]));return result;
  }catch(Exception ex){return null;}
 }
 private static Object steps(HealthSource.Reader db,long start,long end,TimeZone zone){
  try{
   JSONArray rows=query(db,"step_record","key='steps'",start,end,LIMIT);
   Map<String,Map<Long,Long>> sources=new HashMap<>();
   for(int i=0;i<rows.length();i++){
    JSONObject row=rows.getJSONObject(i),v=parse(row);long at=integer(v,"time"),n=integer(v,"steps");
    if(at!=integer(row,"time"))throw new IllegalArgumentException("time mismatch");if(at<=start||at>end)continue;
    Object id=row.opt("sid");if(n<0||!(id instanceof String)||((String)id).trim().isEmpty())throw new IllegalArgumentException("step source/value");
    Map<Long,Long> points=sources.get(id);if(points==null){points=new TreeMap<>();sources.put((String)id,points);}
    Long old=points.put(at,n);if(old!=null&&old.longValue()!=n)throw new IllegalArgumentException("conflicting steps");
   }
   if(sources.isEmpty())return JSONObject.NULL;
   Map<Long,Long> maxima=new TreeMap<>();
   for(Map<Long,Long> points:sources.values()){
    Map<Long,Long> slots=new TreeMap<>();
    for(Map.Entry<Long,Long> p:points.entrySet()){
     // Native step slots are [half-hour,next), not the HR formatter's right-closed measurement bins.
     java.time.ZonedDateTime t=java.time.Instant.ofEpochSecond(p.getKey()).atZone(zone.toZoneId());
     long slot=t.withMinute(t.getMinute()/30*30).withSecond(0).withNano(0).toEpochSecond();
     slots.put(slot,Math.addExact(slots.getOrDefault(slot,0L),p.getValue()));
    }
    for(Map.Entry<Long,Long> slot:slots.entrySet())maxima.put(slot.getKey(),Math.max(maxima.getOrDefault(slot.getKey(),0L),slot.getValue()));
   }
   long sum=0;for(long n:maxima.values())sum=Math.addExact(sum,n);return sum;
  }catch(Exception ex){return JSONObject.NULL;}
 }
 private static Object calories(HealthSource.Reader db,long start,long end,TimeZone zone){
  try{
   JSONArray rows=query(db,"calorie_record","key='calories'",start,end,LIMIT);
   Map<String,Map<Long,java.math.BigDecimal>> sources=new HashMap<>();
   for(int i=0;i<rows.length();i++){
    JSONObject row=rows.getJSONObject(i),v=parse(row);long at=integer(v,"time");Object raw=v.get("calories");
    if(!(raw instanceof Number))throw new IllegalArgumentException("numeric calories");java.math.BigDecimal n=new java.math.BigDecimal(raw.toString());
    if(at!=integer(row,"time"))throw new IllegalArgumentException("time mismatch");if(at<=start||at>end)continue;
    Object id=row.opt("sid");if(n.signum()<0||!(id instanceof String)||((String)id).trim().isEmpty())throw new IllegalArgumentException("calorie source/value");
    Map<Long,java.math.BigDecimal> points=sources.get(id);if(points==null){points=new TreeMap<>();sources.put((String)id,points);}
    java.math.BigDecimal old=points.put(at,n);if(old!=null&&old.compareTo(n)!=0)throw new IllegalArgumentException("conflicting calories");
   }
   if(sources.isEmpty())return JSONObject.NULL;
   Map<Long,java.math.BigDecimal> maxima=new TreeMap<>();
   for(Map<Long,java.math.BigDecimal> points:sources.values()){
    Map<Long,java.math.BigDecimal> slots=new TreeMap<>();
    for(Map.Entry<Long,java.math.BigDecimal> p:points.entrySet()){
     java.time.ZonedDateTime t=java.time.Instant.ofEpochSecond(p.getKey()).atZone(zone.toZoneId());
     long slot=t.withMinute(t.getMinute()/30*30).withSecond(0).withNano(0).toEpochSecond();
     slots.put(slot,slots.getOrDefault(slot,java.math.BigDecimal.ZERO).add(p.getValue()));
    }
    for(Map.Entry<Long,java.math.BigDecimal> slot:slots.entrySet())maxima.put(slot.getKey(),maxima.getOrDefault(slot.getKey(),java.math.BigDecimal.ZERO).max(slot.getValue()));
   }
   // Preserve measured decimals; native natural-day chart rounds each slot only for its displayed report.
   java.math.BigDecimal sum=java.math.BigDecimal.ZERO;for(java.math.BigDecimal n:maxima.values())sum=sum.add(n);return sum;
  }catch(Exception ex){return JSONObject.NULL;}
 }
 private static Object intensity(HealthSource.Reader db,long start,long end){
  try{
   JSONArray rows=query(db,"mh_strength_record","key='intensity'",start,end,LIMIT);Set<Long> times=new HashSet<>();
   for(int i=0;i<rows.length();i++){
    JSONObject row=rows.getJSONObject(i),v=parse(row);long at=integer(v,"time");
    if(at!=integer(row,"time"))throw new IllegalArgumentException("time mismatch");if(at>start&&at<=end)times.add(at);
   }
   // Native MHStrengthItem has only time: count timestamp union, not sid totals or floored minute guesses.
   return times.isEmpty()?JSONObject.NULL:times.size();
  }catch(Exception ex){return JSONObject.NULL;}
 }
 private static Object sports(HealthSource.Reader db,long start,long end){
  try{
   // sport_report.time is a start-based DB key (may preserve dbKeyTime), not end. Keep bounded lookback.
   JSONArray rows=query(db,"sport_report","1=1",start-7*DAY,end,LIMIT);
   Map<String,long[]> unique=new HashMap<>();Map<String,Set<String>> sources=new HashMap<>();Map<String,String> identities=new HashMap<>();
   for(int i=0;i<rows.length();i++){
    JSONObject row=rows.getJSONObject(i),v=parse(row);long a=integer(v,"start_time"),b=integer(v,"end_time"),at=integer(row,"time");
    if(a<=0||b<=a||b-a>7*DAY||(v.has("time")&&integer(v,"time")!=at))throw new IllegalArgumentException("sport bounds/time");
    if(b<=start||b>end)continue;
    Object id=row.opt("sid"),key=row.opt("key");
    if(!(id instanceof String)||((String)id).trim().isEmpty()||!(key instanceof String)||((String)key).trim().isEmpty())throw new IllegalArgumentException("sport identity");
    String projection=a+":"+b+":"+integer(v,"sport_type"),sid=(String)id;
    String identity=new JSONArray().put(sid).put(key).put(at).toString(),old=identities.put(identity,projection);
    if(old!=null&&!old.equals(projection))throw new IllegalArgumentException("conflicting sport identity");
    unique.put(projection,new long[]{a,b});Set<String> ids=sources.get(projection);if(ids==null){ids=new HashSet<>();sources.put(projection,ids);}ids.add(sid);
   }
   // Keep the existing exact projection dedup. Native DB counts identities; it offers no near-match merge.
   // A bounded interval sweep allows disjoint sources, but leaves nonidentical cross-source overlap unknown.
   List<String> sorted=new ArrayList<>(unique.keySet());sorted.sort(Comparator.comparingLong(k->unique.get(k)[0]));
   TreeMap<Long,Set<String>> ends=new TreeMap<>();Map<String,Integer> active=new HashMap<>();
   for(String k:sorted){
    long[] span=unique.get(k);Set<String> ids=sources.get(k);
    while(!ends.isEmpty()&&ends.firstKey()<=span[0])for(String sid:ends.pollFirstEntry().getValue()){
     int n=active.get(sid)-1;if(n==0)active.remove(sid);else active.put(sid,n);
    }
    if(!active.isEmpty()&&(ids.size()!=1||active.size()!=1||!active.containsKey(ids.iterator().next())))return JSONObject.NULL;
    Set<String> ending=ends.get(span[1]);if(ending==null){ending=new HashSet<>();ends.put(span[1],ending);}
    for(String sid:ids)if(ending.add(sid))active.put(sid,active.getOrDefault(sid,0)+1);
   }
   return unique.size();
  }catch(Exception ex){return JSONObject.NULL;}
 }
 private static Object range(List<long[]> points,long a,long b)throws JSONException{
  if(points==null)return JSONObject.NULL;long min=Long.MAX_VALUE,max=Long.MIN_VALUE;for(long[] p:points)if(p[0]>a&&p[0]<=b){min=Math.min(min,p[1]);max=Math.max(max,p[1]);}
  return min==Long.MAX_VALUE?JSONObject.NULL:new JSONObject().put("min",min).put("max",max);
 }
 private static JSONArray bins(List<long[]> points,long start,long end,TimeZone zone)throws JSONException{
  TreeMap<Long,long[]> buckets=new TreeMap<>();
  for(long[] p:points){java.time.ZonedDateTime c=java.time.Instant.ofEpochMilli(p[0]*1000-1).atZone(zone.toZoneId());long a=c.withMinute(c.getMinute()/30*30).withSecond(0).withNano(0).toEpochSecond();
   long[] r=buckets.get(a);if(r==null)buckets.put(a,new long[]{p[1],p[1]});else{r[0]=Math.min(r[0],p[1]);r[1]=Math.max(r[1],p[1]);}
  }
  JSONArray out=new JSONArray();for(Map.Entry<Long,long[]> e:buckets.entrySet())out.put(new JSONObject().put("start_ms",Math.max(start,e.getKey())*1000).put("end_ms",Math.min(end,e.getKey()+1800)*1000).put("min",e.getValue()[0]).put("max",e.getValue()[1]));return out;
 }
}
