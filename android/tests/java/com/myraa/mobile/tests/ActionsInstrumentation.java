package com.myraa.mobile.tests;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.webkit.*;
import android.net.Uri;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

/** Real native bridge/controller; fixture apps and contacts, no external messages or browsing. */
public class ActionsInstrumentation extends Instrumentation {
  Activity activity;WebView web;Object controller;Class<?> actionClass;int checks;final StringBuilder log=new StringBuilder();
  final Map<String,JSONObject> results=new ConcurrentHashMap<>();final List<Intent> opened=new ArrayList<>();
  final Set<String> installed=new HashSet<>(Arrays.asList("com.whatsapp","com.android.chrome","com.google.android.youtube"));
  String failPackage="";int contactQueries;
  @Override public void onCreate(Bundle b){super.onCreate(b);start();}
  void check(boolean pass,String name)throws Exception{if(!pass)throw new Exception(name);checks++;log.append("PASS: ").append(name).append('\n');}
  String js(String source)throws Exception{CountDownLatch latch=new CountDownLatch(1);String[] value={null};runOnMainSync(()->web.evaluateJavascript(source,r->{value[0]=r;latch.countDown();}));if(!latch.await(20,TimeUnit.SECONDS))throw new Exception("UI evaluation timeout");return value[0];}
  void until(java.util.function.BooleanSupplier read,String label)throws Exception{long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(read.getAsBoolean())return;Thread.sleep(80);}throw new Exception(label+" timeout");}
  String request(String name,String args)throws Exception{String id=UUID.randomUUID().toString();js("MyraaNative.mobileAction("+JSONObject.quote(id)+","+JSONObject.quote(name)+","+JSONObject.quote(args)+");true");return id;}
  JSONObject action(String args)throws Exception{String id=request("mobile_action",args);until(()->{if(results.containsKey(id))return true;try{String raw=js("window.__nativeActionResults["+JSONObject.quote(id)+"]||null");if(raw!=null&&!raw.equals("null")){results.put(id,new JSONObject(raw));return true;}}catch(Exception ignored){}return false;},"action result");return results.get(id);}
  Intent last(){return opened.get(opened.size()-1);}
  @Override public void onStart(){Bundle output=new Bundle();try{
    activity=startActivitySync(new Intent().setClassName("com.myraa.mobile","com.myraa.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    ClassLoader loader=activity.getClassLoader();Field view=activity.getClass().getDeclaredField("web");view.setAccessible(true);web=(WebView)view.get(activity);
    runOnMainSync(()->{WebViewClient original=web.getWebViewClient();web.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){if(r.getUrl().getPath().endsWith(".pmx"))return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));return original.shouldInterceptRequest(v,r);}});});
    until(()->{try{return "true".equals(js("!!window.MyraaEvents"));}catch(Exception e){return false;}},"bundled UI");
    js("window.__nativeActionResults={};window.__nativeActionEvents=window.MyraaEvents;window.MyraaEvents=e=>{if(e.type==='mobile-action-result')window.__nativeActionResults[e.id]=e.result;window.__nativeActionEvents(e)};true");
    actionClass=loader.loadClass("com.myraa.mobile.MobileActions");Class<?> resultClass=loader.loadClass("com.myraa.mobile.MobileActions$Result"),launchClass=loader.loadClass("com.myraa.mobile.MobileActions$Launcher"),contactsClass=loader.loadClass("com.myraa.mobile.MobileActions$Contacts"),personClass=loader.loadClass("com.myraa.mobile.MobileActions$Recipient");
    Constructor<?> person=personClass.getConstructor(String.class,String.class);Method emit=activity.getClass().getDeclaredMethod("emit",JSONObject.class);emit.setAccessible(true);
    Object listener=Proxy.newProxyInstance(loader,new Class[]{resultClass},(p,m,a)->{String id=(String)a[0];JSONObject result=(JSONObject)a[1];results.put(id,result);emit.invoke(activity,new JSONObject().put("type","mobile-action-result").put("id",id).put("result",result));return null;});
    Object launcher=Proxy.newProxyInstance(loader,new Class[]{launchClass},(p,m,a)->{
      if(m.getName().equals("installed"))return installed.contains((String)a[0]);
      if(m.getName().equals("app"))return "Calculator".equalsIgnoreCase((String)a[0])?new Intent(Intent.ACTION_MAIN).setClassName("com.fixture.calculator","com.fixture.calculator.Main"):null;
      if(m.getName().equals("open")){Intent intent=new Intent((Intent)a[0]);opened.add(intent);return !failPackage.equals(intent.getPackage());}return null;
    });
    Object contacts=Proxy.newProxyInstance(loader,new Class[]{contactsClass},(p,m,a)->{
      if(m.getName().equals("needsPermission"))return false;
      contactQueries++;String name=(String)a[0];List<Object> list=new ArrayList<>();
      if(name.equals("QA Person"))list.add(person.newInstance("QA Person","919999000001"));
      if(name.equals("Sam")){list.add(person.newInstance("Sam A","919999000002"));list.add(person.newInstance("Sam B","919999000003"));}return list;
    });
    controller=actionClass.getConstructor(Activity.class,resultClass,launchClass,contactsClass).newInstance(activity,listener,launcher,contacts);
    Field actions=activity.getClass().getDeclaredField("mobileActions");actions.setAccessible(true);actions.set(activity,controller);
    JSONObject r=action("{\"action\":\"open_app\",\"app\":\"Chrome\"}");check(r.optString("status").equals("opened")&&last().getPackage().equals("com.android.chrome"),"native Chrome opening targets the installed browser");
    r=action("{\"action\":\"open_app\",\"app\":\"wp\"}");check(r.optString("status").equals("opened")&&last().getPackage().equals("com.whatsapp"),"WP alias opens WhatsApp");
    r=action("{\"action\":\"open_app\",\"app\":\"Calculator\"}");check(last().getComponent().getPackageName().equals("com.fixture.calculator"),"other installed app names resolve to launcher activities");
    r=action("{\"action\":\"search_youtube\",\"query\":\"Hindi songs & rain\"}");check(last().getData().getHost().equals("www.youtube.com")&&last().getData().getQueryParameter("search_query").equals("Hindi songs & rain"),"YouTube search uses the requested encoded query");
    r=action("{\"action\":\"search_web\",\"query\":\"a & b? javascript:alert(1)\"}");check(last().getData().getScheme().equals("https")&&last().getData().getQueryParameterNames().size()==1&&last().getData().getQueryParameter("q").equals("a & b? javascript:alert(1)"),"browser query text cannot become a URL scheme or extra parameter");
    r=action("{\"action\":\"open_wifi_settings\"}");check(r.optString("status").equals("settings_opened")&&last().getAction().equals(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY),"Wi-Fi action opens the Android Internet panel without bypassing system restrictions");
    r=action("{\"action\":\"call\",\"recipient\":\"+919876543210\"}");check(r.optString("status").equals("call_started")&&last().getAction().equals(Intent.ACTION_CALL)&&last().getData().getScheme().equals("tel")&&last().getData().getSchemeSpecificPart().equals("+919876543210"),"call places the call itself for the explicit number instead of opening the dialer");
    int before=opened.size();r=action("{\"action\":\"call\",\"recipient\":\"\"}");check(r.optString("status").equals("invalid")&&opened.size()==before,"a call without a recipient never launches anything");r=action("{\"action\":\"call\",\"recipient\":\"12abc\"}");check(r.optString("status").equals("not_found")&&opened.size()==before,"an unmatched name is reported instead of dialling something guessed");
    r=action("{\"action\":\"call\",\"recipient\":\"QA Person\"}");check(r.optString("status").equals("call_started")&&last().getAction().equals(Intent.ACTION_CALL)&&last().getData().getSchemeSpecificPart().equals("+919999000001"),"saved contact name resolves locally and the call is placed directly");
    failPackage="com.google.android.youtube";r=action("{\"action\":\"search_youtube\",\"query\":\"test\"}");check(r.optString("status").equals("opened")&&last().getPackage()==null,"unavailable YouTube handler falls back to the browser");failPackage="";
    before=opened.size();r=action("{\"action\":\"open_app\",\"app\":\"javascript:alert(1)\"}");check(r.optString("status").equals("not_installed")&&opened.size()==before,"app names never become arbitrary intent or URL targets");
    before=opened.size();r=action("{\"action\":\"send_message\",\"app\":\"whatsapp\",\"recipient\":\"QA Person\",\"text\":\"Hello! Meet at 5?\"}");check(r.optString("status").equals("needs_confirmation")&&opened.size()==before&&r.optString("message").contains("Hello! Meet at 5?"),"preparing a WhatsApp message shows the exact text and touches nothing yet");
    r=action("{\"action\":\"send_message\",\"app\":\"whatsapp\",\"recipient\":\"QA Person\",\"text\":\"Hello! Meet at 5?\",\"confirm\":true}");check(r.optString("status").equals("permission_required")&&r.optString("message").contains("accessibility")&&opened.size()==before,"confirming without the accessibility service names the exact blocker and sends nothing");
    int queries=contactQueries;r=action("{\"action\":\"send_message\",\"app\":\"whatsapp\",\"recipient\":\"+6512345678\",\"text\":\"Hello\"}");check(r.optString("status").equals("needs_confirmation")&&contactQueries==queries,"explicit international phone needs no contact access or guessing");
    before=opened.size();r=action("{\"action\":\"send_message\",\"app\":\"telegram\",\"recipient\":\"not a handle!\",\"text\":\"hi\"}");check(r.optString("status").equals("invalid")&&opened.size()==before,"Telegram needs an exact username, never free text");
    r=action("{\"action\":\"open_url\",\"url\":\"https://example.com/a?b=1\"}");check(r.optString("status").equals("opened")&&last().getAction().equals(Intent.ACTION_VIEW)&&"example.com".equals(last().getData().getHost()),"https links open in the browser");
    before=opened.size();r=action("{\"action\":\"open_url\",\"url\":\"javascript:alert(1)\"}");check(r.optString("status").equals("invalid")&&opened.size()==before,"non-https or script links are refused");
    before=opened.size();r=action("{\"action\":\"open_url\",\"url\":\"file:///data/local/tmp/x\"}");check(r.optString("status").equals("invalid")&&opened.size()==before,"local file links are refused");
    before=opened.size();r=action("{\"action\":\"open_url\",\"url\":\"https://user:secret@example.com/\"}");check(r.optString("status").equals("invalid")&&opened.size()==before,"links with embedded credentials are refused");
    r=action("{\"action\":\"open_settings_panel\",\"panel\":\"bluetooth\"}");check(r.optString("status").equals("settings_opened")&&last().getAction().equals(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),"system panels open through their own intents instead of silent toggles");
    r=action("{\"action\":\"set_alarm\",\"hour\":7,\"minute\":30,\"label\":\"Wake up\"}");check(r.optString("status").equals("alarm_opened")&&last().getAction().equals(android.provider.AlarmClock.ACTION_SET_ALARM)&&last().getIntExtra(android.provider.AlarmClock.EXTRA_HOUR,-1)==7&&last().getIntExtra(android.provider.AlarmClock.EXTRA_MINUTES,-1)==30,"alarm action opens the clock editor with the requested time");
    before=opened.size();r=action("{\"action\":\"set_alarm\",\"hour\":25,\"minute\":0}");check(r.optString("status").equals("invalid")&&opened.size()==before,"invalid alarm times never launch an intent");
    r=action("{\"action\":\"set_timer\",\"seconds\":300}");check(r.optString("status").equals("timer_opened")&&last().getAction().equals(android.provider.AlarmClock.ACTION_SET_TIMER)&&last().getIntExtra(android.provider.AlarmClock.EXTRA_LENGTH,-1)==300,"timer action opens the clock editor with the requested length");
    before=opened.size();r=action("{\"action\":\"send_message\",\"app\":\"sms\",\"recipient\":\"+6512345678\",\"text\":\"Hello\"}");check(r.optString("status").equals("needs_confirmation")&&opened.size()==before,"SMS is prepared as an exact message before anything is sent");r=action("{\"action\":\"cancel_send\"}");check(r.optString("status").equals("cancelled"),"cancel_send discards the prepared message");r=action("{\"action\":\"send_message\",\"confirm\":true}");check(r.optString("status").equals("nothing_pending"),"confirming with nothing prepared sends nothing");
    r=action("{\"action\":\"navigate_to\",\"destination\":\"Howrah Bridge\"}");check(r.optString("status").equals("navigated")&&"geo".equals(last().getData().getScheme())&&last().getData().toString().contains("Howrah%20Bridge"),"navigation opens Maps with the encoded destination");
    r=action("{\"action\":\"copy_text\",\"text\":\"copied\"}");check(r.optString("status").equals("copied"),"clipboard copy reports the real result");
    r=action("{\"action\":\"share_text\",\"text\":\"hello\"}");check(r.optString("status").equals("shared")&&last().getAction().equals(Intent.ACTION_CHOOSER),"sharing opens the Android share sheet");
    r=action("{\"action\":\"device_status\"}");check(r.optString("status").equals("info")&&r.optJSONObject("data")!=null&&r.optJSONObject("data").has("batteryPercent"),"device status returns real local facts");
    r=action("{\"action\":\"list_apps\"}");check(r.optString("status").equals("apps")&&r.optJSONObject("data").optJSONArray("apps")!=null,"installed app list is offered to the agent");
    r=action("{\"action\":\"media_control\",\"command\":\"next\"}");check(r.optString("status").equals("media_sent"),"media keys are dispatched without extra permissions");
    r=action("{\"action\":\"open_app\",\"app\":\"ইউটিউব\"}");check(r.optString("status").equals("opened")&&last().getPackage().equals("com.google.android.youtube"),"a Bengali app name opens the right installed app");
    r=action("{\"action\":\"open_app\",\"app\":\"the youtube app\"}");check(r.optString("status").equals("opened"),"\"the youtube app\" is cleaned up before lookup");
    before=opened.size();r=action("{\"action\":\"open_app\",\"app\":\"zzzzunknownapp\"}");check(r.optString("status").equals("not_installed")&&opened.size()==before,"an unknown app reports honestly and launches nothing");
    r=action("{\"action\":\"play_media\",\"query\":\"Millionaire\",\"app\":\"youtube\"}");check(r.optString("status").equals("play_started")&&last().getPackage().equals("com.google.android.youtube"),"play_media targets the named installed player");
    r=action("{\"action\":\"play_media\",\"query\":\"Triggered Insaan\"}");check(r.optString("status").equals("play_started"),"play_media picks an installed player when none is named");
    before=opened.size();r=action("{\"action\":\"play_media\",\"query\":\"Millionaire\",\"app\":\"spotify\"}");check(r.optString("status").equals("not_installed")&&opened.size()==before,"a missing player is reported instead of opening something else");
    before=opened.size();r=action("{\"action\":\"play_media\",\"query\":\"\"}");check(r.optString("status").equals("invalid")&&opened.size()==before,"play_media never guesses a song");
    before=opened.size();r=action("{\"action\":\"play_media\",\"query\":\"x\",\"app\":\"javascript:alert(1)\"}");check(r.optString("status").equals("invalid")&&opened.size()==before,"unknown players are refused instead of becoming intent targets");
    before=opened.size();r=action("{\"action\":\"create_event\",\"title\":\"x\",\"beginTime\":1}");check(r.optString("status").equals("invalid")&&opened.size()==before,"calendar events outside the supported range are refused");
    before=opened.size();r=action("{\"action\":\"open_settings_panel\",\"panel\":\"../../etc/passwd\"}");check(r.optString("status").equals("settings_opened")&&last().getAction().equals(android.provider.Settings.ACTION_SETTINGS),"unknown panels fall back to the main settings screen");
    String duplicate=request("mobile_action","{\"action\":\"open_app\",\"app\":\"Chrome\"}");until(()->results.containsKey(duplicate),"first result");before=opened.size();js("MyraaNative.mobileAction("+JSONObject.quote(duplicate)+",'mobile_action','{\"action\":\"open_app\",\"app\":\"Chrome\"}');true");Thread.sleep(120);check(opened.size()==before,"duplicate native action IDs do not launch twice");
    Field chooser=actionClass.getDeclaredField("chooser");chooser.setAccessible(true);String choice=request("mobile_action","{\"action\":\"send_message\",\"app\":\"whatsapp\",\"recipient\":\"Sam\",\"text\":\"Hello\"}");until(()->{try{return chooser.get(controller)!=null;}catch(Exception e){return false;}},"contact chooser");
    runOnMainSync(()->{try{((AlertDialog)chooser.get(controller)).getListView().performItemClick(null,1,1);}catch(Exception e){throw new RuntimeException(e);}});until(()->results.containsKey(choice),"chosen contact");check(results.get(choice).optString("status").equals("needs_confirmation")&&results.get(choice).optString("message").contains("Sam B"),"ambiguous contact requires selecting the exact recipient");r=action("{\"action\":\"cancel_send\"}");
    String callChoice=request("mobile_action","{\"action\":\"call\",\"recipient\":\"Sam\"}");until(()->{try{return chooser.get(controller)!=null;}catch(Exception e){return false;}},"call contact chooser");runOnMainSync(()->{try{((AlertDialog)chooser.get(controller)).getListView().performItemClick(null,0,0);}catch(Exception e){throw new RuntimeException(e);}});until(()->results.containsKey(callChoice),"chosen call contact");check(results.get(callChoice).optString("status").equals("call_started")&&last().getAction().equals(Intent.ACTION_CALL)&&last().getData().getSchemeSpecificPart().equals("+919999000002"),"ambiguous saved-contact call asks the user to choose the exact person, then calls");
    installed.remove("com.whatsapp");before=opened.size();r=action("{\"action\":\"send_message\",\"app\":\"whatsapp\",\"recipient\":\"+6512345678\",\"text\":\"Hi\"}");check(r.optString("status").equals("needs_confirmation"),"preparation does not need the app yet");r=action("{\"action\":\"send_message\",\"app\":\"whatsapp\",\"recipient\":\"+6512345678\",\"text\":\"Hi\",\"confirm\":true}");check(r.optString("status").equals("not_installed")&&opened.size()==before,"missing WhatsApp is reported honestly");
    check(activity.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED,"opening the app did not obtain SIM access");
    r=action("{\"action\":\"sim_info\"}");
    check(r.optString("status").equals("unavailable")&&r.optString("message").contains("No SIM access request")&&activity.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED,"SIM details report unavailable without requesting access");
    before=opened.size();r=action("{\"action\":\"call\",\"recipient\":\"+919876543210\",\"sim\":\"SIM 99\"}");
    check(r.optString("status").equals("unavailable")&&opened.size()==before,"an unavailable SIM choice never silently uses a different account");
    getUiAutomation().grantRuntimePermission("com.myraa.mobile",android.Manifest.permission.READ_PHONE_STATE);
    r=action("{\"action\":\"sim_info\"}");
    check(r.optString("status").equals("info")&&r.optJSONObject("data").optJSONArray("sims")!=null,"SIM info still works for a previously granted phone-state permission");
    r=action("{\"action\":\"delete_files\"}");check(r.optString("status").equals("unsupported"),"unsupported device actions cannot execute");
    String bad=request("runShell","{}");until(()->results.containsKey(bad),"unsupported tool");check(results.get(bad).optString("status").equals("unsupported"),"desktop and shell tools are rejected by the native bridge");
    Field foreground=activity.getClass().getDeclaredField("foreground");foreground.setAccessible(true);foreground.setBoolean(activity,false);before=opened.size();r=action("{\"action\":\"open_app\",\"app\":\"Chrome\"}");foreground.setBoolean(activity,true);check(r.optString("status").equals("permission_required")&&opened.size()==before,"background switching requires visible floating controls or returning to MYRAA");
    boolean chromeAvailable;try{activity.getPackageManager().getPackageInfo("com.android.chrome",0);chromeAvailable=true;}catch(Exception missing){chromeAvailable=false;}
    if(chromeAvailable){
      Object real=actionClass.getConstructor(Activity.class,resultClass).newInstance(activity,listener);actions.set(activity,real);
      r=action("{\"action\":\"open_app\",\"app\":\"Chrome\"}");
      until(()->{android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();return root!=null&&"com.android.chrome".contentEquals(root.getPackageName());},"actual Chrome foreground");
      check(r.optString("status").equals("opened"),"production native action actually brings installed Chrome to the foreground");
      android.os.ParcelFileDescriptor command=getUiAutomation().executeShellCommand("am start -n com.myraa.mobile/.MainActivity");new android.os.ParcelFileDescriptor.AutoCloseInputStream(command).close();
    }else log.append("SKIP: real Chrome foreground check; Chrome is not installed.\n");
    output.putString("stream",log+"Checks: "+checks+"; fixture apps and contacts; no messages sent.\n");finish(Activity.RESULT_OK,output);
  }catch(Exception e){output.putString("stream",log+"FAIL: "+e.getMessage());finish(Activity.RESULT_CANCELED,output);}finally{if(activity!=null)runOnMainSync(()->activity.finish());}}
}
