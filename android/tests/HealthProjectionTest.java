package cn.local.micompanion;
import org.json.*;
import java.util.TimeZone;

/** Synthetic fixtures only; never use copied device values. */
public final class HealthProjectionTest {
 private static final long NOW=1767355200000L; // 2026-01-02 12:00 UTC
 private static final TimeZone UTC=TimeZone.getTimeZone("UTC");
 private static int checks;
 private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 private static JSONObject json(String text)throws Exception{return new JSONObject(text);}
 public static void main(String[] args)throws Exception{
  // Mutations caught: guessed calorie scale, copied sid/raw lists, or zero treated as missing.
  JSONObject step=HealthProjection.daily("STEP",json("{\"steps\":0,\"distance\":2400,\"calories\":123000,\"sid\":\"SECRET\",\"stepRecords\":[{\"did\":\"SECRET\"}]}"),1767312000L,1767350000000L,NOW,UTC);
  check(step.getInt("steps")==0,"zero steps retained");
  check(step.getDouble("distance_m")==2400,"distance stored in metres");
  check(step.isNull("step_kcal"),"unverified STEP calorie unit withheld");
  check(!step.toString().contains("SECRET"),"whitelist excludes identifiers");
  check(step.getBoolean("is_today")&&!step.getBoolean("is_historical"),"UTC date label not local midnight epoch");
  JSONObject old=HealthProjection.record("WEIGHT",json("{\"weight\":71.25,\"time\":1609459200,\"sid\":\"SECRET\"}"),1609459200L,NOW,UTC);
  check(old.getDouble("kg")==71.25&&old.getBoolean("is_historical"),"old weight survives without seven day cutoff");
  check(old.getLong("sample_time_epoch_seconds")==1609459200L&&old.getString("report_date").equals("2021-01-01"),"real measurement date not collection date");
  JSONObject sugar=HealthProjection.record("BLOOD_SUGAR",json("{\"blood_sugar\":6.4,\"measurement_period\":2}"),1609459200L,NOW,UTC);
  check(sugar.getDouble("mmol_per_l")==6.4,"blood sugar unit");
  JSONObject hr=HealthProjection.daily("HR",json("{\"avgHr\":71,\"minHr\":0,\"maxHr\":115,\"restHr\":62,\"latestHrRecord\":{\"bpm\":80,\"time\":1609459200,\"sid\":\"SECRET\"}}"),1767312000L,1767350000000L,NOW,UTC);
  check(hr.getInt("average_bpm")==71&&hr.isNull("minimum_bpm"),"invalid zero HR is missing not a real measurement");
  check(!hr.toString().contains("SECRET"),"summary ignores raw nested identifiers");
  JSONObject sleep=HealthProjection.record("SLEEP",json("{\"duration\":430,\"bedtime\":1767225600,\"wake_up_time\":1767251400,\"sleep_deep_duration\":80,\"sleep_light_duration\":270,\"sleep_trace_duration\":60,\"sleep_awake_duration\":20,\"awake_count\":3,\"items\":[]}"),1767251400L,NOW,UTC);
  check(sleep.isNull("rem_minutes")&&sleep.getInt("trace_minutes")==60&&sleep.getInt("deep_minutes")==80,"trace is not REM, no inferred remainder");
  check(sleep.getLong("wake_up_epoch_seconds")==1767251400L,"sleep uses wake measurement epoch");
  check(HealthProjection.record("SPO2",json("{\"spo2\":101}"),1767350000L,NOW,UTC).isNull("percent"),"out of range oxygen withheld");
  check(HealthProjection.record("HR",json("{\"bpm\":\"NaN\"}"),1767350000L,NOW,UTC).isNull("bpm"),"nonfinite number withheld");
  JSONObject train=HealthProjection.daily("TRAINING_LOAD",json("{\"wtlSum\":210,\"tlLevel\":2,\"sid\":\"SECRET\"}"),1609459200L,1767350000000L,NOW,UTC);
  check(train.getInt("weekly_load")==210&&train.getBoolean("is_historical"),"other health summary retains report label");
  JSONObject missing=HealthProjection.daily("STEP",new JSONObject(),1767312000L,0,NOW,UTC);
  check(missing.isNull("steps"),"absent number is not zero");
  check(HealthProjection.daily("CALORIE",json("{\"calories\":99000}"),1767312000L,0,NOW,UTC).getDouble("activity_kcal")==99000,"CALORIE getter directly displayed in kcal");
  check(HealthProjection.daily("MH_STRENGTH",json("{\"duration\":23}"),1767312000L,0,NOW,UTC).getInt("moderate_vigorous_minutes")==23,"moderate vigorous duration minutes");
  check(HealthProjection.record("BLOOD_PRESSURE",json("{\"systolic_pressure\":123,\"diastolic_pressure\":81}"),1767350000L,NOW,UTC).getInt("systolic_mmhg")==123,"blood pressure explicit unit");
  // Metadata alone must not make a metric look available.
  check(!HealthProjection.hasMeasurement(missing),"missing metric not promoted by metadata");
  JSONObject goals=HealthProjection.goals(json("{\"goal_items\":[{\"field\":1,\"target_value\":8000,\"achieved_value\":456},{\"field\":2,\"target_value\":400,\"achieved_value\":123},{\"field\":4,\"target_value\":30,\"achieved_value\":23},{\"field\":99,\"target_value\":999}]}"),1767312000L,NOW,UTC);
  check(goals.getJSONObject("steps").getInt("target")==8000,"goal field STEP code is mapped");
  check(goals.getJSONObject("activity_kcal").getInt("achieved")==123,"goal calorie unit no rescaling");
  check(!goals.toString().contains("999"),"unknown goal codes withheld");
  JSONObject future=HealthProjection.record("HR",json("{\"bpm\":75}"),1767455200L,NOW,UTC);
  check(future.isNull("bpm"),"future measurement not accepted");
  System.out.println("HealthProjection: "+checks+" checks passed");
 }
}
