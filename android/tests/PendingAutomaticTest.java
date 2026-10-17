package cn.local.micompanion;
/** The retry gate must not run every two-second screen sample, and old triggers cannot become current snapshots. */
public final class PendingAutomaticTest {
 private static boolean invoke(String method,long first,long second){
  return "expired".equals(method)?PendingAutomatic.expired(first,second):PendingAutomatic.retryDue(first,second);
 }
 private static void check(boolean b,String name){if(!b)throw new AssertionError(name);}
 public static void main(String[] args){
  check(!invoke("expired",1000,86_401_000L),"exactly 24 hours may still submit");
  check(invoke("expired",1000,86_401_001L),"more than 24 hours cannot create a current snapshot");
  check(invoke("expired",-1,86_401_001L),"legacy UUID with unknown creation time must not be revived as current");
  check(invoke("expired",400_000,0),"wall clock rollback beyond tolerance cannot extend age");
  check(invoke("retryDue",-1,0),"first attempt is immediate");
  check(!invoke("retryDue",0,2000),"pending UUID must not bypass one-minute gate");
  check(!invoke("retryDue",0,59999),"one-minute boundary not yet reached");
  check(invoke("retryDue",0,60000),"one-minute retry becomes due");
  System.out.println("PendingAutomatic: 8 checks passed");
 }
}
