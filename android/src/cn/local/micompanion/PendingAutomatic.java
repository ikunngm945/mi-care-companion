package cn.local.micompanion;

/** Shared policy for durable automatic-trigger age and monotonic worker scheduling. */
public final class PendingAutomatic {
 private PendingAutomatic() { }
 public static boolean expired(long createdWall,long nowWall){
  return createdWall<0||createdWall<nowWall-86_400_000L||createdWall>nowWall+300_000L;
 }
 public static boolean retryDue(long lastAttemptElapsed,long nowElapsed){
  return lastAttemptElapsed<0||nowElapsed-lastAttemptElapsed>=60_000L;
 }
}
