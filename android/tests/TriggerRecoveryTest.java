package cn.local.micompanion;

/** Replays real UsageEvents codes against the production recovery/state logic. */
public final class TriggerRecoveryTest {
 private static int checks;
 private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
 private static TriggerState recover(TriggerState saved,boolean sameBoot,long checkpoint,long now,boolean oldOn,boolean oldOpen,boolean on,boolean open,boolean evidence,long[][] events){
  return TriggerRecovery.restore(saved,sameBoot,checkpoint,checkpoint+1_000_000_000L,now,now+1_000_000_000L,oldOn,oldOpen,on,open,evidence,events);
 }
 public static void main(String[] args){
  TriggerState s=new TriggerState();s.screenOff(0);
  s=recover(s,true,1000,10_800_002L,false,false,true,true,true,new long[][]{{1_000_000_000L,16},{1_010_800_001L,15},{1_010_800_002L,18}});
  check(s.isArmed(),"same-boot proven off interval through process absence earns qualification");
  check(!s.tick(11_400_002L,true,true),"replayed unlock preserves strict use boundary");
  check(s.tick(11_400_003L,true,true),"replayed unlock can trigger only after ten minutes");

  s=new TriggerState();s.screenOff(0);
  s=recover(s,true,1000,11_000_000L,false,false,true,true,true,new long[][]{{1_000_000_000L,16},{1_005_000_000L,15},{1_005_000_001L,18},{1_006_000_000L,16},{1_011_000_000L,15}});
  check(!s.isArmed(),"intervening screen transitions prevent concatenating short off intervals");

  s=new TriggerState();s.screenOff(0);
  s=recover(s,true,1000,11_000_000L,false,false,true,true,false,new long[0][]);
  check(!s.isArmed(),"unavailable evidence cannot manufacture off qualification");
  s=new TriggerState();s.screenOff(0);
  s=recover(s,false,1000,11_000_000L,false,false,true,true,true,new long[][]{{1_000_000_000L,16},{1_011_000_000L,15}});
  check(!s.isArmed(),"cross-boot elapsed time is never accumulated");

  s=new TriggerState();s.screenOff(0);s.screenOn(10_800_001L,true);
  s=recover(s,true,10_900_000L,11_900_000L,true,true,true,true,true,new long[][]{{1_010_800_001L,15},{1_010_800_002L,18},{1_011_000_000L,17},{1_011_800_000L,18}});
  check(!s.tick(11_900_000L,true,true),"lock during absent process resets previously running use timer");
  check(s.tick(12_400_001L,true,true),"qualification survives interruption and new full use interval");

  s=new TriggerState();s.screenOff(0);s.screenOn(10_800_001L,true);
  s=recover(s,true,10_900_000L,11_900_000L,true,true,true,true,true,new long[0][]);
  check(s.isArmed()&&!s.tick(11_900_000L,true,true),"empty unanchored query preserves only proven eligibility");
  s=new TriggerState();s.screenOff(0);s.screenOn(10_800_001L,true);
  s=recover(s,false,10_900_000L,1000,true,true,true,true,true,new long[0][]);
  check(s.isArmed()&&!s.tick(1000,true,true),"reboot keeps proven qualification but discards previous use");
  s=new TriggerState();s.screenOff(0);
  s=recover(s,true,1000,11_000_000L,false,false,true,true,true,new long[][]{{1_000_000_000L,16}});
  check(!s.isArmed(),"missing final screen-on evidence resets continuity rather than guessing");
  System.out.println("TriggerRecovery: "+checks+" checks passed");
 }
}
