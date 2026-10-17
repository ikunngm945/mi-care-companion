package cn.local.micompanion;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Real official HTTP + production write-ahead state machine, no live model/robot. */
public final class ClientTransportTest {
    public static void main(String[] args)throws Exception {
        StringBuilder calls=new StringBuilder();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1",exchange->{
            if(!"Bearer local-test-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(401,-1);exchange.close();return;}
            String path=exchange.getRequestURI().getPath();String response;String type="application/json";
            if(path.endsWith("/im/bots"))response="{\"status\":\"ok\",\"data\":{\"bot_ids\":[\"local\"]}}";
            else if(path.endsWith("/chat/sessions"))response="{\"status\":\"ok\",\"data\":{\"sessions\":[],\"page\":1,\"page_size\":1,\"total\":0}}";
            else if(path.endsWith("/config-routes"))response="{\"status\":\"ok\",\"data\":{\"routing\":{}}}";
            else if(path.endsWith("/config-profiles"))response="{\"status\":\"ok\",\"data\":{\"info_list\":[{\"id\":\"default\",\"name\":\"default\",\"path\":\"unused\"}]}}";
            else if(path.endsWith("/chat")){
                calls.append("model,");type="text/event-stream";
                response="data: {\"type\":\"plain\",\"data\":\"生成正文\",\"streaming\":true,\"chain_type\":null}\n\ndata: {\"type\":\"end\",\"data\":\"\"}\n\n";
            }else{
                calls.append("send,");
                try{
                    java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();byte[] buf=new byte[1024];int n;
                    while((n=exchange.getRequestBody().read(buf))!=-1)b.write(buf,0,n);
                    JSONObject body=new JSONObject(new String(b.toByteArray(),StandardCharsets.UTF_8));
                    if(!"生成正文".equals(body.getString("message"))||!"local:FriendMessage:1".equals(body.getString("umo")))throw new Exception();
                }catch(Exception error){exchange.sendResponseHeaders(400,-1);exchange.close();return;}
                response="{\"status\":\"ok\",\"data\":{}}";
            }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try{
            SettingsStore.Config cfg=new SettingsStore.Config("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1","local-test-key","local:FriendMessage:1",true);
            String[] state={"pending"};
            OfficialDelivery.Steps io=new OfficialDelivery.Steps(){
                public long now(){return 2;}
                public void checkpoint(String next,String text){state[0]=next;calls.append(next+",");}
                public void check()throws Exception{OfficialApi.check(cfg);}
                public String generate()throws Exception{return OfficialApi.generate(cfg,"00000000-0000-0000-0000-000000000001","{\"health\":null}");}
                public void send(String text)throws Exception{assert state[0].equals("sending");OfficialApi.send(cfg,text);}
            };
            OfficialDelivery.run("pending",null,1,2,io);
            assert "sent".equals(state[0]);assert "generating,model,ready,sending,send,sent,".equals(calls.toString()):calls;
            OfficialDelivery.run("sent",null,1,2,io);
            assert "generating,model,ready,sending,send,sent,".equals(calls.toString());
            System.out.println("ClientTransportTest: PASS (official real HTTP + durable generate-before-send + terminal no-repeat)");
        }finally{server.stop(0);}
    }
}
