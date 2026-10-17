package cn.local.micompanion;

/** Validates UsageEvents evidence before bridging a same-boot process gap. No Android dependencies. */
public final class TriggerRecovery {
 private TriggerRecovery() { }
 public static TriggerState restore(TriggerState saved,boolean sameBoot,long lastElapsed,long lastWall,
   long nowElapsed,long nowWall,boolean lastOn,boolean lastOpen,boolean currentOn,boolean currentOpen,
   boolean queryAvailable,long[][] events) {
  TriggerState original=TriggerState.decode(saved.encode());
  long gap=nowElapsed-lastElapsed;
  // Query retention and wall/monotonic mapping are bounded. A changed wall clock is not timing evidence.
  if(!sameBoot||!queryAvailable||gap<0||gap>86_400_000L||(nowWall-lastWall)!=gap||events==null)
   return reset(original,nowElapsed,currentOn,currentOpen);
  int screen=-1,lock=-1;long previous=Long.MIN_VALUE;
  for(long[] e:events){
   if(e==null||e.length!=2||e[0]<previous)return reset(original,nowElapsed,currentOn,currentOpen);
   previous=e[0];if(e[0]>lastWall)continue;
   if(e[1]==15||e[1]==16)screen=(int)e[1];
   if(e[1]==17||e[1]==18)lock=(int)e[1];
  }
  // An empty/trimmed query cannot establish that no screen transitions were lost.
  if(screen!=(lastOn?15:16))return reset(original,nowElapsed,currentOn,currentOpen);
  TriggerState result=TriggerState.decode(original.encode());
  boolean on=lastOn,open=lastOpen;
  if(lastOn&&lock!=(lastOpen?18:17)){
   result.tick(lastElapsed,true,false,false);open=false;
  }
  for(long[] e:events){
   if(e[0]<=lastWall||e[0]>nowWall)continue;
   long at=lastElapsed+(e[0]-lastWall);
   if(at>nowElapsed)return reset(original,nowElapsed,currentOn,currentOpen);
   switch((int)e[1]){
    case 15: on=true;open=false;result.screenOn(at,false);break;
    case 16: on=false;open=false;result.screenOff(at);break;
    case 17: open=false;result.tick(at,on,false,false);break;
    case 18: open=true;if(on)result.unlock(at);break;
    default: break;
   }
  }
  if(on!=currentOn)return reset(original,nowElapsed,currentOn,currentOpen);
  // A missing keyguard event invalidates use continuity, not an independently proven long off period.
  if(open!=currentOpen)result.tick(nowElapsed,on,false,false);
  result.tick(nowElapsed,currentOn,currentOpen,false);
  if(currentOn&&currentOpen)result.unlock(nowElapsed);
  return result;
 }
 private static TriggerState reset(TriggerState state,long now,boolean on,boolean open){
  state.restart();if(on)state.screenOn(now,open);else state.screenOff(now);return state;
 }
}
