package cn.local.micompanion;
public class DelayedEventTest {
 public static void main(String[] args){
  if(ClientProtocol.eventTime(false,1000,5000)!=1000)throw new AssertionError("automatic identity must retain trigger time");
  if(ClientProtocol.eventTime(true,1000,5000)!=5000)throw new AssertionError("manual collects now independent of pending automatic");
  try{ClientProtocol.eventTime(false,1000,86402000);throw new AssertionError("late creation must expire");}catch(IllegalArgumentException expected){}
  System.out.println("DelayedEvent: 3 checks passed");
 }
}
