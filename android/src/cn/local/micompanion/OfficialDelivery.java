package cn.local.micompanion;

/** Local write-ahead barriers for a non-idempotent official API. */
final class OfficialDelivery {
    interface Steps {
        void checkpoint(String stage,String generated) throws Exception;
        long now();
        void check() throws Exception;
        default void verifyTarget() throws Exception { }
        String generate() throws Exception;
        void send(String text) throws Exception;
    }
    static void run(String stage,String text,long created,long now,Steps io)throws Exception {
        if ("sent".equals(stage) || "delivery_unknown".equals(stage) || "generation_unknown".equals(stage)
                || "generation_failed".equals(stage) || "expired".equals(stage)) return;
        if ("sending".equals(stage)) { io.checkpoint("delivery_unknown",text); return; }
        if ("generating".equals(stage)) { io.checkpoint("generation_unknown",text); return; }
        if (!("pending".equals(stage) || "retry".equals(stage) || "ready".equals(stage) || "paused".equals(stage))) {
            io.checkpoint("paused",text); return;
        }
        if (!ClientProtocol.withinSubmissionWindow(created,now)) { io.checkpoint("expired",null); return; }
        io.check(); // No model or IM side effects; safe to retry a failed preflight.
        if (!"ready".equals(stage)) {
            io.verifyTarget();
            io.checkpoint("generating",null); // Must be durable before POST /chat.
            if (!ClientProtocol.withinSubmissionWindow(created,io.now())) { io.checkpoint("expired",null); return; }
            try { io.verifyTarget(); }
            catch(Exception changed){io.checkpoint("pending",null);throw changed;}
            try { text=io.generate(); }
            catch (Exception failure) { io.checkpoint("generation_failed",null); return; }
            if (text==null || text.trim().isEmpty()) { io.checkpoint("generation_failed",null); return; }
            io.checkpoint("ready",text); // A storage failure must not fall through to IM.
        }
        if (!ClientProtocol.withinSubmissionWindow(created,io.now())) { io.checkpoint("expired",null); return; }
        io.verifyTarget();
        io.checkpoint("sending",text); // Must be durable before the non-idempotent IM call.
        if (!ClientProtocol.withinSubmissionWindow(created,io.now())) { io.checkpoint("expired",null); return; }
        try { io.verifyTarget(); }
        catch(Exception changed){io.checkpoint("ready",text);throw changed;}
        try { io.send(text); }
        catch (Exception failure) { io.checkpoint("delivery_unknown",text); return; }
        io.checkpoint("sent",null);
    }
}
