package cn.local.micompanion.tests;

import android.app.Instrumentation;
import android.os.Bundle;
import cn.local.micompanion.AdvicePrompt;
import cn.local.micompanion.SettingsStore;

/** Explicit one-time, local-only prompt update. No endpoint or model is contacted. */
public final class WarmPromptProbe extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=0;
        try{
            SettingsStore.Config before=SettingsStore.load(getTargetContext());
            require(AdvicePrompt.DEFAULT.contains("你好，现在是")&&AdvicePrompt.DEFAULT.contains("不说教"),"installed warm default unavailable");
            String monitorBefore=getTargetContext().getSharedPreferences("monitor",0).getAll().toString();
            SettingsStore.save(getTargetContext(),before.baseUrl,before.key,before.umo,before.allowHttp,AdvicePrompt.DEFAULT);
            SettingsStore.Config after=SettingsStore.load(getTargetContext());
            require(before.baseUrl.equals(after.baseUrl)&&before.key.equals(after.key)&&before.umo.equals(after.umo)&&before.allowHttp==after.allowHttp,"non-prompt config changed");
            require(AdvicePrompt.DEFAULT.equals(after.prompt),"warm prompt was not persisted");
            require(monitorBefore.equals(getTargetContext().getSharedPreferences("monitor",0).getAll().toString()),"monitor settings changed");
            result.putString("prompt","PASS: approved warm default persisted; other config and monitor unchanged");
            result.putString("network","none: local encrypted save/load only; no model, no IM");
        }catch(Throwable e){code=1;result.putString("failure",e instanceof AssertionError?e.getMessage():e.getClass().getSimpleName());}
        finish(code,result);
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
