package cn.local.micompanion;

/** Exercises production durability barriers without Android SQLite mocks. */
public final class OfficialDeliveryTest {
    static class IO implements OfficialDelivery.Steps {
        String stage="pending",text; String calls=""; boolean lostSend, lostGenerate, failReady,failSending,expireGenerating,expireChecking;
        public void checkpoint(String s,String t) throws Exception {calls+="save:"+s+",";if(failReady&&s.equals("ready")||failSending&&s.equals("sending"))throw new Exception("disk");stage=s;text=t;}
        long wall=2;
        public long now(){return wall;}
        public void check(){calls+="check,";if(expireChecking)wall=86400002L;}
        public String generate() throws Exception {calls+="generate,";if(lostGenerate)throw new Exception("stream lost");if(expireGenerating)wall=86400002L;return "生成正文";}
        public void send(String t) throws Exception {assert stage.equals("sending");assert t.equals("生成正文");calls+="send,";if(lostSend)throw new Exception("response lost");}
    }
    public static void main(String[] args)throws Exception{
        IO ok=new IO();OfficialDelivery.run("pending",null,1,2,ok);assert ok.stage.equals("sent");assert ok.calls.equals("check,save:generating,generate,save:ready,save:sending,send,save:sent,"):ok.calls;
        IO lost=new IO();lost.lostSend=true;OfficialDelivery.run("pending",null,1,2,lost);assert lost.stage.equals("delivery_unknown");String previous=lost.calls;OfficialDelivery.run(lost.stage,lost.text,1,2,lost);assert previous.equals(lost.calls);
        IO restart=new IO();OfficialDelivery.run("sending","生成正文",1,2,restart);assert restart.stage.equals("delivery_unknown")&&!restart.calls.contains("send,");
        IO generating=new IO();OfficialDelivery.run("generating",null,1,2,generating);assert generating.stage.equals("generation_unknown")&&!generating.calls.contains("generate,");
        IO bad=new IO();bad.lostGenerate=true;OfficialDelivery.run("pending",null,1,2,bad);assert bad.stage.equals("generation_failed")&&!bad.calls.contains("send,");
        IO disk=new IO();disk.failReady=true;try{OfficialDelivery.run("pending",null,1,2,disk);throw new AssertionError();}catch(Exception expected){}assert !disk.calls.contains("send,")&&disk.stage.equals("generating");
        IO sendDisk=new IO();sendDisk.failSending=true;try{OfficialDelivery.run("ready","生成正文",1,2,sendDisk);throw new AssertionError();}catch(Exception expected){}assert !sendDisk.calls.contains("send,");
        IO expired=new IO();OfficialDelivery.run("ready","生成正文",1,86400002L,expired);assert expired.stage.equals("expired")&&!expired.calls.contains("send,");
        IO cached=new IO();OfficialDelivery.run("ready","生成正文",1,2,cached);assert cached.stage.equals("sent")&&!cached.calls.contains("generate,");
        IO legacy=new IO();OfficialDelivery.run("accepted",null,1,2,legacy);assert legacy.stage.equals("paused")&&!legacy.calls.contains("send,");
        IO late=new IO();late.expireGenerating=true;OfficialDelivery.run("pending",null,1,2,late);assert late.stage.equals("expired")&&!late.calls.contains("send,");
        IO preflight=new IO();preflight.expireChecking=true;OfficialDelivery.run("pending",null,1,2,preflight);assert preflight.stage.equals("expired")&&!preflight.calls.contains("generate,")&&!preflight.calls.contains("send,");
        class TargetIO extends IO {
            int guard;int changeAt;
            public void verifyTarget(){if(++guard==changeAt)throw new IllegalArgumentException("changed target");}
        }
        for(int point:new int[]{1,2,3,4}){
            TargetIO changed=new TargetIO();changed.changeAt=point;
            try{OfficialDelivery.run("pending",null,1,2,changed);}catch(IllegalArgumentException expected){}
            assert !changed.calls.contains("send,"):"changed target sent IM at guard "+point;
            if(point<3){assert !changed.calls.contains("generate,"):"changed target generated at guard "+point;assert changed.stage.equals("pending");}
            else assert changed.stage.equals("ready"):"unattempted IM lost ready text";
        }
        System.out.println("OfficialDelivery: 16 checks passed (durable barriers, no duplicate post, lost reply, expiry, legacy, changed target)");
    }
}
