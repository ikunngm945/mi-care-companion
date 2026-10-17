package cn.local.micompanion;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;
/** Offline, create-only export of the same deterministic view used before chat POST. */
public final class HealthPreview {
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("usage: HealthPreview input new-health.txt new-model.json");
  Path input=Paths.get(args[0]);byte[] original=Files.readAllBytes(input);
  String result=HealthText.snapshot(WeatherText.snapshot(new String(original,StandardCharsets.UTF_8)));
  JSONObject after=new JSONObject(result);
  if(!java.util.Arrays.equals(original,Files.readAllBytes(input)))throw new AssertionError("input changed");
  Files.write(Paths.get(args[1]),after.getJSONObject("health").getString("facts").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
  Files.write(Paths.get(args[2]),result.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
  System.out.println("Offline health/model exports created; no network or event submission");
 }
}
