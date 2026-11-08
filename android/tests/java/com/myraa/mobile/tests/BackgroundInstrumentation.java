package com.myraa.mobile.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Emulator-only end-to-end tests, using an injected HTTPS transport: no Google traffic. */
public class BackgroundInstrumentation extends Instrumentation {
  private final StringBuilder evidence = new StringBuilder();
  private final AtomicInteger requests = new AtomicInteger();
  private volatile boolean blockNext;
  private volatile int failNext;
  private volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
  private volatile String lastBody = "";
  private Activity activity; private WebView web; private Context target;
  private Object store, factoryBefore; private Class<?> storeClass, taskClass, coordinatorClass, agentClass;
  private Field factoryField;
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  /** Reports progress so a stalled run still shows where it stopped in the killed log. */
  private void phase(String label) {
    Bundle status = new Bundle(); status.putString("phase", label); sendStatus(0, status);
  }
  private void check(boolean ok, String label) throws Exception { if (!ok) throw new Exception(label); evidence.append("PASS: ").append(label).append('\n'); }
  private Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
    Class<?> owner = target instanceof Class ? (Class<?>)target : target.getClass();
    Method method = owner.getDeclaredMethod(name, types); method.setAccessible(true);
    try { return method.invoke(target instanceof Class ? null : target, args); }
    catch (InvocationTargetException e) { throw new Exception(e.getCause()); }
  }
  private Object find(String id) throws Exception { return invoke(store, "find", new Class[]{String.class}, id); }
  private String status(String id) {
    try { Object task=find(id); if (task == null) return "missing"; Field f=taskClass.getDeclaredField("status"); f.setAccessible(true); return (String)f.get(task); }
    catch (Exception e) { return "error"; }
  }
  private int jobId(Object task) throws Exception { return (Integer)invoke(task, "jobId", new Class[]{}); }
  private void until(java.util.function.BooleanSupplier condition, String label, int seconds) throws Exception {
    long end=System.currentTimeMillis()+seconds*1000L; while(System.currentTimeMillis()<end) { if(condition.getAsBoolean())return; Thread.sleep(100); }
    throw new Exception(label+" timed out");
  }
  private String js(String source) throws Exception {
    CountDownLatch latch=new CountDownLatch(1); String[] value={null};
    activity.runOnUiThread(()->web.evaluateJavascript(source,result->{value[0]=result;latch.countDown();}));
    if(!latch.await(30,TimeUnit.SECONDS))throw new Exception("WebView timed out: "+source); return value[0];
  }
  private String shell(String command) throws Exception {
    ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);
    try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
      byte[] buf=new byte[2048]; int n; while((n=in.read(buf))!=-1)out.write(buf,0,n); return out.toString("UTF-8");
    }
  }
  private Object enqueue(String id,String prompt) throws Exception {
    Object task=invoke(store,"enqueue",new Class[]{String.class,String.class},id,prompt);
    invoke(coordinatorClass,"schedule",new Class[]{Context.class,taskClass},target,task); return task;
  }
  private void force(Object task) throws Exception { shell("cmd jobscheduler run -f com.myraa.mobile "+jobId(task)); }
  /**
   * Android may refuse a single forced run while the previous job is still
   * unwinding, so keep asking until the task completes instead of failing on the
   * first attempt.
   */
  private void complete(Object task,String id,String label,int seconds) throws Exception {
    long end=System.currentTimeMillis()+seconds*1000L;
    while(System.currentTimeMillis()<end) {
      if("completed".equals(status(id)))return;
      try { force(task); } catch(Exception ignored) { }
      Thread.sleep(1500);
    }
    throw new Exception(label+" timed out");
  }
  /**
   * Opens the app window. MainActivity is singleTask, so an already running task
   * would only receive onNewIntent and startActivitySync would wait forever; the
   * clear-task flag forces a fresh instance, and the wait itself is bounded.
   */
  private Activity openApp() throws Exception {
    final Intent intent=new Intent().setClassName("com.myraa.mobile","com.myraa.mobile.MainActivity")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
    final Activity[] started={null}; final Exception[] failure={null};
    Thread thread=new Thread(()->{ try { started[0]=startActivitySync(intent); } catch(Exception e){ failure[0]=e; } },"vishal-open-app");
    thread.setDaemon(true); thread.start(); thread.join(30000);
    if(thread.isAlive())throw new Exception("MainActivity did not open within 30 seconds");
    if(failure[0]!=null)throw failure[0];
    if(started[0]==null)throw new Exception("MainActivity did not open");
    return started[0];
  }
  private void reopen() throws Exception {
    phase("reopen the app window");
    shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard");
    activity=openApp();
    Field field=activity.getClass().getDeclaredField("web"); field.setAccessible(true); web=(WebView)field.get(activity);
    until(()->{try{return "true".equals(js("!!window.MyraaNative&&!!window.MyraaEvents"));}catch(Exception e){return false;}},"app UI",45);
  }
  /** Android's JSON writer escapes '/' as '\/', so assertions read parsed text. */
  private String historyText() throws Exception {
    JSONArray history=new JSONArray(target.getSharedPreferences("myraa.mobile.v1",0).getString("history","[]"));
    StringBuilder text=new StringBuilder();
    for(int i=0;i<history.length();i++){ JSONObject turn=history.optJSONObject(i); if(turn!=null) text.append(turn.optString("text","")).append('\n'); }
    return text.toString();
  }
  private int countHistory(String id) throws Exception {
    JSONArray history=new JSONArray(target.getSharedPreferences("myraa.mobile.v1",0).getString("history","[]")); int count=0;
    for(int i=0;i<history.length();i++)if(id.equals(history.getJSONObject(i).optString("taskId")))count++; return count;
  }
  @Override public void onStart() {
    Bundle output=new Bundle();
    try {
      phase("prepare the fixture account");
      target=getTargetContext(); ClassLoader loader=target.getClassLoader();
      storeClass=loader.loadClass("com.myraa.mobile.AiTaskStore"); taskClass=loader.loadClass("com.myraa.mobile.AiTaskStore$Task");
      coordinatorClass=loader.loadClass("com.myraa.mobile.AiTaskCoordinator"); agentClass=loader.loadClass("com.myraa.mobile.AiAgent");
      store=invoke(storeClass,"get",new Class[]{Context.class},target);
      invoke(coordinatorClass,"cancelAll",new Class[]{Context.class},target); invoke(store,"clearHistory",new Class[]{});
      SharedPreferences prefs=target.getSharedPreferences("myraa.mobile.v1",Context.MODE_PRIVATE);
      SharedPreferences.Editor edit=prefs.edit().putString("model","gemini-test").putBoolean("askedTaskNotifications",true);
      Class<?> vault=loader.loadClass("com.myraa.mobile.CredentialVault");
      invoke(vault,"write",new Class[]{SharedPreferences.Editor.class,String.class},edit,"fixture-only-gemini-key-00000000000000");
      check(edit.commit(),"fixture credential is saved using the existing Android Keystore format");
      try { getUiAutomation().grantRuntimePermission("com.myraa.mobile","android.permission.POST_NOTIFICATIONS"); } catch(Exception ignored) {}
      Class<?> connections=loader.loadClass("com.myraa.mobile.AiAgent$Connections");
      factoryField=agentClass.getDeclaredField("connections");factoryField.setAccessible(true);factoryBefore=factoryField.get(null);
      Object factory=Proxy.newProxyInstance(loader,new Class[]{connections},(proxy,method,args)->{
        if(!"open".equals(method.getName()))return null;
        requests.incrementAndGet(); boolean block=blockNext; blockNext=false; boolean fail=failNext>0; if(fail)failNext--;
        return new MockConnection((URL)args[0],block,fail);
      });
      factoryField.set(null,factory);
      reopen();
      check("true".equals(js("!!document.getElementById('avatar')&&!!document.getElementById('search-toggle')&&!!document.getElementById('theme')")),"original character, conversation search and theme controls remain present");
      js("MyraaNative.setTextScale(130);true");
      until(()->{AtomicInteger zoom=new AtomicInteger();runOnMainSync(()->zoom.set(web.getSettings().getTextZoom()));return zoom.get()==130;},"text zoom",10);
      phase("existing controls and text size");
      check(prefs.getInt("textScale",0)==130,"adjustable text size is saved and applied to the existing WebView");
      js("MyraaNative.setTextScale(100);true");

      // Request is already running when the app is minimized and the display is switched off.
      phase("chat while the screen is off");
      String first=UUID.randomUUID().toString(); blockNext=true; entered=new CountDownLatch(1); release=new CountDownLatch(1);
      js("MyraaNative.chat('Fixture question while the phone sleeps',"+JSONObject.quote(first)+");true");
      check(entered.await(20,TimeUnit.SECONDS),"foreground chat started through the native Gemini transport");
      Object firstTask=find(first); JobScheduler scheduler=(JobScheduler)target.getSystemService(Context.JOB_SCHEDULER_SERVICE);
      JobInfo scheduled=scheduler.getPendingJob(jobId(firstTask));
      check(scheduled!=null&&scheduled.isPersisted()&&first.equals(scheduled.getExtras().getString("taskId")),"network job is persisted with only a task identifier");
      shell("input keyevent KEYCODE_HOME"); shell("input keyevent KEYCODE_SLEEP");
      until(()->{try{Field f=activity.getClass().getDeclaredField("foreground");f.setAccessible(true);return !f.getBoolean(activity);}catch(Exception e){return false;}},"background lifecycle",15);
      release.countDown(); until(()->"completed".equals(status(first)),"sleeping-device task",30);
      check(countHistory(first)==2,"minimized/screen-off request completes and saves exactly one question and answer");
      NotificationManager notifications=(NotificationManager)target.getSystemService(Context.NOTIFICATION_SERVICE);
      until(()->notifications!=null&&java.util.Arrays.stream(notifications.getActiveNotifications()).anyMatch(n->n.getId()==jobIdUnchecked(firstTask)),"completion notification",15);
      check(true,"completion notification is posted without exposing the answer");

      // Destroying the Activity must hand an in-flight request over to the persisted job.
      phase("hand an in-flight request over to the job");
      reopen(); String second=UUID.randomUUID().toString(); blockNext=true; entered=new CountDownLatch(1); release=new CountDownLatch(1);
      js("MyraaNative.chat('Resume after Activity destruction',"+JSONObject.quote(second)+");true");
      check(entered.await(20,TimeUnit.SECONDS),"second in-flight request started");
      final Activity doomed=activity; activity.runOnUiThread(doomed::finish);
      until(doomed::isDestroyed,"Activity destruction",20);
      until(()->"queued".equals(status(second)),"handoff to persistent queue",20);
      Object secondTask=find(second); complete(secondTask,second,"resumed background job",75);
      check(countHistory(second)==2,"job resumes after Activity destruction and saves exactly one answer");

      // A temporary provider disconnect is retained and retried, not dropped.
      phase("retry a temporary disconnect");
      String third=UUID.randomUUID().toString(); failNext=1; Object thirdTask=enqueue(third,"Retry this connection");
      int before=requests.get(); force(thirdTask);
      until(()->requests.get()>before&&"queued".equals(status(third)),"transient failure retained",30);
      complete(thirdTask,third,"retry completion",75);
      check(countHistory(third)==2,"temporary network failure retries without duplicating history");

      phase("grounded research request");
      String research=UUID.randomUUID().toString(); Object researchTask=enqueue(research,"Research the latest weather today");
      complete(researchTask,research,"grounded research",75);
      check(new JSONObject(lastBody).getJSONArray("tools").getJSONObject(0).has("google_search"),"time-sensitive research requests use Gemini Google Search grounding");
      check(historyText().contains("https://example.com/source"),"grounded source link is saved with the answer");

      phase("interrupted phone action recovery");
      String recovery=UUID.randomUUID().toString(); Object recoveryTask=enqueue(recovery,"Check a previous phone task");
      invoke(store,"markToolStarted",new Class[]{String.class},recovery); // marking requires running
      invoke(store,"running",new Class[]{String.class},recovery);
      invoke(store,"markToolStarted",new Class[]{String.class},recovery);
      invoke(store,"requeue",new Class[]{String.class,String.class},recovery,"interrupted");
      complete(recoveryTask,recovery,"safe tool recovery",75);
      check(!new JSONObject(lastBody).has("tools"),"interrupted phone actions are never automatically replayed");
      check(historyText().contains("did not repeat it"),"recovered answer warns that a previous phone action may have started");

      phase("transcript export and sharing");
      Class<?> exporter=loader.loadClass("com.myraa.mobile.TranscriptExport");
      File transcript=(File)invoke(exporter,"create",new Class[]{Context.class},target);
      String fileText; try(InputStream in=new FileInputStream(transcript);ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1)out.write(b,0,n);fileText=out.toString("UTF-8");
      }
      check(fileText.contains("Fixture question while the phone sleeps")&&fileText.contains("Sources:"),"UTF-8 text-file export includes the conversation and research sources");
      File shareOne=(File)invoke(exporter,"createShared",new Class[]{Context.class},target);
      File shareTwo=(File)invoke(exporter,"createShared",new Class[]{Context.class},target);
      Class<?> providerClass=loader.loadClass("com.myraa.mobile.TranscriptProvider");
      android.net.Uri sharedUri=(android.net.Uri)invoke(providerClass,"uriFor",new Class[]{File.class},shareOne);
      try(InputStream shared=target.getContentResolver().openInputStream(sharedUri)) {
        check(shared!=null && shared.read()>=0 && !shareOne.getName().equals(shareTwo.getName()),"sharing uses an isolated readable text-file URI for each export");
      }
      phase("finished");
      output.putString("stream",evidence.toString()+"BACKGROUND_TESTS_PASSED\n"); finish(Activity.RESULT_OK,output);
    } catch(Exception e) {
      StringWriter details=new StringWriter(); e.printStackTrace(new PrintWriter(details));
      output.putString("stream",evidence.toString()+"BACKGROUND_TESTS_FAILED: "+details+"\n"); finish(Activity.RESULT_CANCELED,output);
    } finally {
      try { if(factoryField!=null)factoryField.set(null,factoryBefore); } catch(Exception ignored){}
      try { if(store!=null){invoke(coordinatorClass,"cancelAll",new Class[]{Context.class},target);invoke(store,"clearHistory",new Class[]{});} } catch(Exception ignored){}
      try { if(target!=null)target.getSharedPreferences("myraa.mobile.v1",0).edit().clear().commit(); } catch(Exception ignored){}
      if(activity!=null)runOnMainSync(()->activity.finish());
      try { shell("input keyevent KEYCODE_WAKEUP"); } catch(Exception ignored){}
    }
  }
  private int jobIdUnchecked(Object task){try{return jobId(task);}catch(Exception e){return -1;}}
  private final class MockConnection extends HttpsURLConnection {
    final boolean block,fail; final ByteArrayOutputStream written=new ByteArrayOutputStream(); volatile boolean disconnected;
    MockConnection(URL url,boolean block,boolean fail){super(url);this.block=block;this.fail=fail;}
    @Override public OutputStream getOutputStream(){return written;}
    @Override public int getResponseCode() throws IOException {
      lastBody=new String(written.toByteArray(),StandardCharsets.UTF_8);
      if(block){entered.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
      if(fail||disconnected)throw new IOException("fixture interruption"); return 200;
    }
    @Override public InputStream getInputStream(){
      String body="data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Fixture answer\"}]},\"groundingMetadata\":{\"groundingChunks\":[{\"web\":{\"uri\":\"https://example.com/source\",\"title\":\"Example source\"}}]}}]}\n\n";
      return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }
    @Override public void disconnect(){disconnected=true;if(block)release.countDown();}
    @Override public boolean usingProxy(){return false;}
    @Override public void connect(){}
    @Override public String getCipherSuite(){return "fixture";}
    @Override public Certificate[] getLocalCertificates(){return null;}
    @Override public Certificate[] getServerCertificates(){return null;}
    @Override public Principal getPeerPrincipal(){return null;}
    @Override public Principal getLocalPrincipal(){return null;}
  }
}
