package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.app.SearchManager;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.*;
import android.view.KeyEvent;
import org.json.*;
import java.util.*;

/** Bounded Android intents and direct device actions for the Vishal Jarvis agent.
 *
 *  Direct actions (calls, SMS, third-party chat sends) are permission-gated, always
 *  preceded by the user's explicit instruction, and report only what actually happened.
 *  There is no accessibility access outside UiAutomationService, no shell, no
 *  arbitrary URL intent and no silent background sending. */
public final class MobileActions {
  public static final int CONTACTS_REQUEST=88;
  public static final int TORCH_REQUEST=89;
  public static final int PHONE_REQUEST=91;
  private static final int MAX_APPS=150;
  private static final long PENDING_TTL=5*60*1000L;
  private static final String SMS_RECEIPT="com.myraa.mobile.SMS_RECEIPT";
  /** Music players that accept media-browse intents, in the order I try them. */
  private static final LinkedHashMap<String,String> PLAYERS=new LinkedHashMap<>();
  /** Bengali / Banglish app names the user says, mapped to what Android can resolve. */
  private static final String[][] APP_ALIASES={
    {"ইউটিউব","youtube"},{"ইউ টিউব","youtube"},{"यूट्यूब","youtube"},
    {"হোয়াটসঅ্যাপ","whatsapp"},{"হোয়াটস এপ","whatsapp"},{"व्हाट्सएप","whatsapp"},{"व्हाट्सऐप","whatsapp"},
    {"ইনস্টাগ্রাম","instagram"},{"इंस्टाग्राम","instagram"},
    {"ফেসবুক","facebook"},{"फेसबुक","facebook"},
    {"টেলিগ্রাম","telegram"},{"টেলিগ্ৰাম","telegram"},{"टेलीग्राम","telegram"},
    {"মেসেঞ্জার","messenger"},{"मैसेंजर","messenger"},
    {"ক্যালকুলেটর","calculator"},{"कैलकुलेटर","calculator"},
    {"ক্যামেরা","camera"},{"कैमरा","camera"},
    {"গ্যালারি","gallery"},{"गैलरी","gallery"},
    {"সেটিংস","settings"},{"सेटिंग्स","settings"},
    {"প্লে স্টোর","play store"},{"गুগল প্লে স্টোর","play store"},{"प्ले स्टोर","play store"},
    {"জিমেইল","gmail"},{"ग्मेल","gmail"},
    {"ফোন","phone"},{"फोन","phone"},
    {"কন্ট্যাক্টস","contacts"},{"কনট্যাক্ট","contacts"},{"संपर्क","contacts"},
    {"ম্যাপস","maps"},{"ম্যাপ","maps"},{"मैप्स","maps"},
    {"ক্লক","clock"},{"ঘড়ি","clock"},{"घड़ी","clock"},
    {"ফাইলস","files"},{"ফাইল","files"},{"फाइल","files"},
    {"ক্রোম","chrome"},{"গুগল ক্রোম","chrome"},
    {"স্পটিফাই","spotify"},{"गाना","gaana"},{"গানা","gaana"}
  };
  private static final String[] PLAYER_ORDER={"youtube_music","youtube","spotify","prime_music","gaana","jiosaavn"};
  static{
    PLAYERS.put("youtube","com.google.android.youtube");
    PLAYERS.put("youtube_music","com.google.android.apps.youtube.music");
    PLAYERS.put("spotify","com.spotify.music");
    PLAYERS.put("prime_music","com.amazon.mp3");
    PLAYERS.put("gaana","com.gaana");
    PLAYERS.put("jiosaavn","com.jio.media.ondemand");
  }

  public interface Result { void complete(String id,JSONObject result); }
  public interface Launcher { boolean installed(String pkg); Intent app(String label); boolean open(Intent intent); }
  public interface Contacts { boolean needsPermission(); List<Recipient> find(String name); }
  public static final class Recipient {
    public final String name,phone;
    public final double score;
    public Recipient(String name,String phone){this(name,phone,1);}
    public Recipient(String name,String phone,double score){this.name=name;this.phone=phone;this.score=score;}
  }
  public interface Speech { void speak(String text); }
  private final Activity activity;
  private final Result result;
  private final Launcher launcher;
  private final Contacts contacts;
  private ContactMatcher matcher;
  private Speech speech;
  private Runnable pending;
  private String pendingId;
  private String pendingPhonePermission;
  private String pendingTorchId;
  private Boolean pendingTorchState;
  private AlertDialog chooser;
  private String chooserId;
  private JSONObject pendingSend;
  private final Map<String,JSONObject> completed=new LinkedHashMap<>();
  private boolean permissionsGranted=false;
  private boolean gateOpen=false;
  private ProActions proActions;
  private ProActions pro(){if(proActions==null)proActions=new ProActions(activity,(id,status,message,data)->finish(id,status,message,data));return proActions;}
  private final String smsToken=UUID.randomUUID().toString();
  private boolean smsReceiverRegistered;
  private final BroadcastReceiver smsReceiver=new BroadcastReceiver(){
    @Override public void onReceive(Context context,Intent intent){
      if(intent==null||!SMS_RECEIPT.equals(intent.getAction()))return;
      if(!smsToken.equals(intent.getStringExtra("token")))return;
      String id=intent.getStringExtra("id"),to=intent.getStringExtra("to");
      if(id==null||to==null)return;
      boolean ok=getResultCode()==Activity.RESULT_OK;
      result.complete(id,status(ok?"sms_delivered_to_network":"sms_failed",ok
        ?"SMS accepted by the network for +"+to+"."
        :"Android could not send the SMS (code "+getResultCode()+"). Nothing was sent.",null));
    }
  };

  public MobileActions(Activity activity,Result result) {
    this(activity,result,new Launcher(){
      public boolean installed(String pkg){try{activity.getPackageManager().getPackageInfo(pkg,0);return true;}catch(Exception e){return false;}}
      public Intent app(String label){
        PackageManager pm=activity.getPackageManager();
        Intent main=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String wanted=normalize(label);
        if(wanted.length()<3)return null; // never guess from one or two letters
        Intent prefix=null,contains=null;
        for(ResolveInfo info:pm.queryIntentActivities(main,0)){
          if(!info.activityInfo.exported)continue;
          String candidate=normalize(info.loadLabel(pm).toString());
          if(candidate.equals(wanted))return launcherIntent(info);
          if(prefix==null&&candidate.startsWith(wanted))prefix=launcherIntent(info);
          // "contains" only when the label is long enough for the match to be meaningful.
          if(contains==null&&wanted.length()>=5&&candidate.contains(wanted))contains=launcherIntent(info);
        }
        return prefix!=null?prefix:contains;
      }
      private Intent launcherIntent(ResolveInfo info){
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
          .setClassName(info.activityInfo.packageName,info.activityInfo.name);
      }
      public boolean open(Intent intent){
        if(intent==null)return false;
        try{
          activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
          return true;
        }catch(ActivityNotFoundException|SecurityException missing){
          // A stale shortcut or a removed app must not crash or look like a silent failure.
          return false;
        }catch(RuntimeException broken){
          android.util.Log.w("MyraaActions","Intent could not be started: "+broken.getClass().getSimpleName());
          return false;
        }
      }
    },new Contacts() {
      public boolean needsPermission(){
        // The test seam injects its own Contacts, so this branch only runs in the real app.
        // Reading the field here is illegal before the super constructor; read the grant live.
        return activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED;
      }
      public List<Recipient> find(String name){
        List<Recipient> found=new ArrayList<>();Set<String> seen=new HashSet<>();
        String escaped=name.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
        try(Cursor c=activity.getContentResolver().query(Phone.CONTENT_URI,new String[]{Phone.DISPLAY_NAME,Phone.NUMBER,Phone.NORMALIZED_NUMBER},Phone.DISPLAY_NAME+" LIKE ? ESCAPE '\\'",new String[]{"%"+escaped+"%"},Phone.DISPLAY_NAME+" ASC")){
          if(c!=null)while(c.moveToNext()&&found.size()<20){
            String raw=c.getString(2);if(raw==null||raw.isEmpty())raw=c.getString(1);
            String phone=international(raw,activity);if(phone!=null&&seen.add(phone))found.add(new Recipient(c.getString(0),phone));
          }
        }
        List<Recipient> exact=new ArrayList<>();for(Recipient r:found)if(normalize(r.name).equals(normalize(name)))exact.add(r);
        return exact.isEmpty()?found:exact;
      }
    });
    try {
      IntentFilter filter=new IntentFilter(SMS_RECEIPT);
      if(Build.VERSION.SDK_INT>=33)activity.registerReceiver(smsReceiver,filter,Context.RECEIVER_EXPORTED);
      else activity.registerReceiver(smsReceiver,filter);
      smsReceiverRegistered=true;
    } catch(RuntimeException ignored){smsReceiverRegistered=false;}
    this.matcher=new ContactMatcher(activity,new ContactAliasStore(activity.getSharedPreferences("myraa.mobile.v1",0)),activity.getSharedPreferences("myraa.mobile.v1",0));
  }
  /** Test seam: injected fakes run with permissions treated as granted. */
  public MobileActions(Activity activity,Result result,Launcher launcher,Contacts contacts){this.activity=activity;this.result=result;this.launcher=launcher;this.contacts=contacts;this.permissionsGranted=true;}
  public void setSpeech(Speech speech){this.speech=speech;}
  public static String normalize(String input){return input==null?"":input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
  private static String international(String raw,Activity activity){
    if(raw==null)return null;String clean=raw.replaceAll("[ ()-]","");if(clean.startsWith("00"))clean="+"+clean.substring(2);
    if(clean.matches("\\+[1-9][0-9]{7,14}"))return clean.substring(1);
    String country="";try{country=activity.getSystemService(TelephonyManager.class).getNetworkCountryIso();}catch(Exception ignored){}
    if(country==null||country.length()!=2){String language=activity.getSharedPreferences("myraa.mobile.v1",0).getString("language","bn-IN");country=language.endsWith("IN")?"IN":"US";}
    String e164=PhoneNumberUtils.formatNumberToE164(raw,country.toUpperCase(Locale.ROOT));return e164==null?null:e164.substring(1);
  }
  /** Digits only, no plus sign, using the device's country for local numbers. */
  private static String numberDigits(String raw,Activity activity){
    if(raw==null)return null;
    String clean=raw.trim().replaceAll("[^0-9+]","");
    if(clean.isEmpty())return null;
    if(clean.startsWith("00"))clean="+"+clean.substring(2);
    if(clean.matches("\\+[1-9][0-9]{7,14}"))return clean.substring(1);
    if(clean.length()>=11&&clean.matches("[1-9][0-9]{7,14}"))return clean;
    String e164=international(raw,activity);
    return e164;
  }
  public static Intent search(String target,String query){
    if(query==null||query.trim().isEmpty()||query.length()>1000)throw new IllegalArgumentException("Say what you want to search for.");
    Uri uri="youtube".equals(target)?Uri.parse("https://www.youtube.com/results").buildUpon().appendQueryParameter("search_query",query.trim()).build():Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q",query.trim()).build();
    return new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE);
  }
  public static Intent web(String raw){
    if(raw==null)throw new IllegalArgumentException("Say which page to open.");
    String value=raw.trim();
    if(value.isEmpty()||value.length()>2048)throw new IllegalArgumentException("Include the full page address.");
    if(!value.matches("(?i)https?://[^\\s]+"))throw new IllegalArgumentException("Only https links can be opened. Give the full address including https://.");
    Uri uri=Uri.parse(value);
    String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
    String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
    if(!scheme.equals("https")&&!scheme.equals("http"))throw new IllegalArgumentException("Only https links can be opened.");
    if(host.isEmpty()||!host.contains(".")||host.equals("localhost")||host.endsWith(".local")||host.endsWith(".internal"))throw new IllegalArgumentException("That address is not a public web page.");
    if(uri.getUserInfo()!=null)throw new IllegalArgumentException("Links with embedded credentials are not allowed.");
    return new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE);
  }
  private static JSONObject json(Object... values){
    JSONObject value=new JSONObject();
    try{for(int i=0;i<values.length;i+=2)value.put(String.valueOf(values[i]),values[i+1]);}catch(JSONException ignored){}
    return value;
  }
  private static JSONObject status(String status,String message,JSONObject data){
    JSONObject value=new JSONObject();
    try{value.put("status",status).put("message",message);if(data!=null)value.put("data",data);}catch(JSONException ignored){}
    return value;
  }
  private void finish(String id,String status,String message){finish(id,status,message,null);}
  private void finish(String id,String status,String message,JSONObject data){
    // A completed action must never leave a stale dialog or permission waiter behind,
    // otherwise every later action reports "busy" until the app restarts.
    if(id!=null&&id.equals(pendingId)&&!"busy".equals(status)){pending=null;pendingId=null;pendingPhonePermission=null;}
    if(id!=null&&id.equals(chooserId)&&!"busy".equals(status)){if(chooser!=null){try{chooser.dismiss();}catch(RuntimeException ignored){}}chooser=null;chooserId=null;}
    publish(id,status(status,message,data));
  }
  private void publish(String id,JSONObject value){
    completed.put(id,value);while(completed.size()>64)completed.remove(completed.keySet().iterator().next());result.complete(id,value);
  }
  private boolean granted(String permission){return permissionsGranted||activity.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;}
  /** Real OS grants only: SIM and call-account data cannot be faked by a test seam. */
  private boolean hasPermission(String permission){return activity.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;}
  private void requestCallPermission(String id,Runnable retry){
    if(pending!=null||pendingTorchId!=null){finish(id,"busy","Finish the current permission request first.");return;}
    pendingId=id;pending=retry;pendingPhonePermission=Manifest.permission.CALL_PHONE;
    activity.requestPermissions(new String[]{Manifest.permission.CALL_PHONE},PHONE_REQUEST);
  }
  private void requestSmsPermission(String id,Runnable retry){
    if(pending!=null||pendingTorchId!=null){finish(id,"busy","Finish the current permission request first.");return;}
    pendingId=id;pending=retry;pendingPhonePermission=Manifest.permission.SEND_SMS;
    activity.requestPermissions(new String[]{Manifest.permission.SEND_SMS},PHONE_REQUEST);
  }

  public void execute(String id,String name,String raw){
    if(id==null||!id.matches("[A-Za-z0-9-]{1,80}"))return;
    if(completed.containsKey(id)){result.complete(id,completed.get(id));return;}
    if(!"mobile_action".equals(name)){finish(id,"unsupported","That mobile action is unavailable.");return;}
    try{
      JSONObject args=new JSONObject(raw);String action=args.optString("action");
      if(!gateOpen&&SecureGate.protects(activity,action,args)){
        final String reason="call".equals(action)?"Call "+limit(args.optString("recipient"),60,"this contact"):"Send "+limit(args.optString("app"),20,"the")+" message to "+limit(args.optString("recipient"),60,"this contact");
        SecureGate.ask(activity,reason,new SecureGate.Done(){
          public void allowed(){gateOpen=true;try{execute(id,name,raw);}finally{gateOpen=false;}}
          public void denied(String message){finish(id,"auth_failed",message);}
        });
        return;
      }
      if(ProActions.supports(action)){pro().handle(id,action,args);return;}
      if("open_app".equals(action)){openApp(id,args.optString("app"));return;}
      if("search_youtube".equals(action)||"search_web".equals(action)){
        boolean youtube=action.equals("search_youtube");Intent intent=search(youtube?"youtube":"web",args.optString("query"));String pkg=youtube?"com.google.android.youtube":"com.android.chrome";
        if(launcher.installed(pkg))intent.setPackage(pkg);else if(youtube&&launcher.installed("com.android.chrome"))intent.setPackage("com.android.chrome");
        boolean opened=launcher.open(intent);if(!opened&&intent.getPackage()!=null)opened=launcher.open(new Intent(intent).setPackage(null));
        finish(id,opened?"opened":"unavailable",opened?(youtube?"YouTube":"Browser")+" search opened.":"No app can open this search on this device.");return;
      }
      if("open_url".equals(action)){openUrl(id,args.optString("url"));return;}
      if("play_media".equals(action)){playMedia(id,args);return;}
      if("call".equals(action)){call(id,args.optString("recipient"),args.optString("sim"));return;}
      if("send_message".equals(action)){sendMessage(id,args);return;}
      if("cancel_send".equals(action)){cancelSend(id);return;}
      if("sim_info".equals(action)){simInfo(id);return;}
      if("set_flashlight".equals(action)){setFlashlight(id,args.optBoolean("enabled",false));return;}
      if("call_assistant".equals(action)){callAssistant(id,args);return;}
      if("open_wifi_settings".equals(action)){openWifiSettings(id);return;}
      if("open_settings_panel".equals(action)){openPanel(id,args.optString("panel"));return;}
      if("set_alarm".equals(action)){setAlarm(id,args.optInt("hour",-1),args.optInt("minute",-1),args.optString("label"));return;}
      if("set_timer".equals(action)){setTimer(id,args.optInt("seconds",-1),args.optString("label"));return;}
      if("create_event".equals(action)){createEvent(id,args);return;}
      if("navigate_to".equals(action)){navigate(id,args.optString("destination"));return;}
      if("share_text".equals(action)){share(id,args.optString("text"));return;}
      if("copy_text".equals(action)){copy(id,args.optString("text"));return;}
      if("media_control".equals(action)){media(id,args.optString("command"));return;}
      if("set_volume".equals(action)){volume(id,args);return;}
      if("device_status".equals(action)){deviceStatus(id);return;}
      if("list_apps".equals(action)){listApps(id);return;}
      if("open_camera".equals(action)){openCamera(id);return;}
      // Actions that only exist as headless tools (screen reading, recall, proactive summaries)
      // are answered by the registry. Every action with a window handler above keeps using that
      // handler, so permission prompts, choosers and confirmations behave exactly as before.
      if(ActionRegistry.newOnly(action)){
        JSONObject direct=ActionRegistry.run(activity,action,args);
        finish(id,direct.optString("status","failed"),direct.optString("message",""),direct);
        return;
      }
      finish(id,"unsupported","Supported actions: open apps, open a web page, search YouTube/web, play a song or video in YouTube, YouTube Music, Spotify, Prime Music, Gaana or JioSaavn, call a number or saved contact (direct call, optional SIM), send WhatsApp/Telegram/Instagram/Messenger/SMS messages after confirmation, flashlight, Wi-Fi and system panels, alarms, timers, calendar events, navigation, share, copy, media and volume control, device status, SIM info, installed apps, reminders, notes, notifications, calendar, weather, daily briefing and find-my-phone.");
    }catch(IllegalArgumentException e){finish(id,"invalid",e.getMessage());}catch(Exception e){finish(id,"failed","Android could not complete this action. Nothing was sent.");}
  }

  // ---------------------------------------------------------------- direct calls

  private void call(String id,String recipient,String sim){
    String value=recipient==null?"":recipient.trim();
    if(value.isEmpty()||value.length()>100){finish(id,"invalid","Whom should I call? Give a saved name or a phone number.");return;}
    String digits=numberDigits(value,activity);
    if(digits!=null){placeCall(id,digits,value,sim);return;}
    if(contacts.needsPermission()&&!granted(Manifest.permission.READ_CONTACTS)){
      if(pending!=null||pendingTorchId!=null){finish(id,"busy","Finish the current permission request first.");return;}
      pendingId=id;pending=()->call(id,recipient,sim);
      activity.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_REQUEST);return;
    }
    String spoken=NameMatcher.extractSpokenName(value);
    matchPerson(id,spoken.isEmpty()?value:spoken,true,(person)->{
      if(matcher!=null)matcher.rememberAlias(spoken,person.name);
      placeCall(id,person.phone,person.name,sim);
    });
  }

  private void placeCall(String id,String digits,String label,String sim){
    if(!granted(Manifest.permission.CALL_PHONE)){requestCallPermission(id,()->placeCall(id,digits,label,sim));return;}
    List<PhoneAccountHandle> accounts=callAccounts();
    PhoneAccountHandle handle=null;
    int choice=simIndex(sim);
    if(choice>0 && (accounts.isEmpty() || choice>accounts.size())){
      finish(id,"unavailable","I cannot select that SIM. Phone-state access or that account is unavailable. Use the phone’s default call account or choose it in the system dialer.");return;
    }
    if(accounts.size()>1){
      if(choice<1){
        finish(id,"needs_sim","This phone has "+accounts.size()+" SIMs: "+simLabels(accounts)+". Which SIM should I call from?",json("sims",simArray(accounts)));
        return;
      }
      handle=accounts.get(Math.min(choice,accounts.size())-1);
    }else if(accounts.size()==1){
      // Only pin the account when the user asked for a SIM. Pinning the only visible account
      // makes some phones refuse the call outright (the account may not be call-capable).
      if(choice>0)handle=accounts.get(0);
    }
    try{
      Intent intent=new Intent(Intent.ACTION_CALL,Uri.fromParts("tel","+"+digits,null));
      if(handle!=null)intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,handle);
      boolean placed=launcher.open(intent);
      if(!placed){
        // Some phones refuse ACTION_CALL from the background or with an unknown account, but the
        // dialer still opens pre-filled. The call is then one tap away and nothing is pretended.
        placed=launcher.open(new Intent(Intent.ACTION_DIAL,Uri.fromParts("tel","+"+digits,null)));
        if(placed){
          finish(id,"dialer_opened","Android would not place the call itself, so the dialer is open with "+label+" ready. Tap the green call button to finish.");
          return;
        }
      }
      if(placed){
        String simNote=accounts.size()>1&&choice>0?" on SIM "+choice:"";
        finish(id,"call_started","Calling "+label+simNote+". The call is being placed now.");
      }else{
        finish(id,"failed","Android did not start the call. Check the Phone permission and that a dialer app exists.");
      }
    }catch(SecurityException e){
      finish(id,"permission_required","Allow the Phone permission for Vishal Jarvis so it can place calls directly.");
    }catch(RuntimeException e){
      finish(id,"failed","Android did not start the call. Nothing was dialled.");
    }
  }

  private int simIndex(String sim){
    String value=normalize(sim);
    if(value.isEmpty()||value.equals("default")||value.equals("any")||value.equals("auto"))return -1;
    String digits=value.replaceAll("[^0-9]","");
    if(digits.isEmpty())return -1;
    try{return Integer.parseInt(digits);}catch(NumberFormatException e){return -1;}
  }

  private List<PhoneAccountHandle> callAccounts(){
    List<PhoneAccountHandle> accounts=new ArrayList<>();
    if(!hasPermission(Manifest.permission.READ_PHONE_STATE))return accounts;
    try{
      TelecomManager telecom=(TelecomManager)activity.getSystemService(Context.TELECOM_SERVICE);
      if(telecom==null)return accounts;
      List<PhoneAccountHandle> found=telecom.getCallCapablePhoneAccounts();
      if(found!=null)accounts.addAll(found);
    }catch(RuntimeException ignored){}
    return accounts;
  }

  private String simLabels(List<PhoneAccountHandle> accounts){
    StringBuilder text=new StringBuilder();
    for(int i=0;i<accounts.size();i++){
      if(i>0)text.append(", ");
      text.append(i+1).append(") ").append(simLabel(accounts.get(i)));
    }
    return text.toString();
  }

  private String simLabel(PhoneAccountHandle handle){
    try{
      TelecomManager telecom=(TelecomManager)activity.getSystemService(Context.TELECOM_SERVICE);
      PhoneAccount account=telecom.getPhoneAccount(handle);
      if(account!=null){
        CharSequence label=account.getLabel();
        if(label!=null&&label.length()>0)return label.toString();
      }
    }catch(RuntimeException ignored){}
    return "SIM "+(handle.getId()==null?"":handle.getId());
  }

  private JSONArray simArray(List<PhoneAccountHandle> accounts){
    JSONArray array=new JSONArray();
    for(int i=0;i<accounts.size();i++)array.put(json("sim",i+1,"label",simLabel(accounts.get(i))));
    return array;
  }

  private void simInfo(String id){
    if(!hasPermission(Manifest.permission.READ_PHONE_STATE)){
      finish(id,"unavailable","SIM details are unavailable without phone-state access. No SIM access request was made.");return;
    }
    List<PhoneAccountHandle> accounts=callAccounts();
    if(accounts.isEmpty()){finish(id,"info","This phone reports no SIM accounts.",json("sims",new JSONArray()));return;}
    finish(id,"info","This phone has "+accounts.size()+" SIM"+(accounts.size()==1?"":"s")+": "+simLabels(accounts)+".",json("sims",simArray(accounts)));
  }

  // ---------------------------------------------------------------- sending

  private void sendMessage(String id,JSONObject args){
    // A confirmation refers to the message that was already prepared and shown,
    // so it must not require the model to repeat the app, recipient or text.
    if(args.optBoolean("confirm",false)){confirmSend(id);return;}
    String app=normalize(args.optString("app","whatsapp"));
    if(app.isEmpty())app="whatsapp";
    String text=args.optString("text","").trim();
    if(text.isEmpty()||text.length()>4000){finish(id,"invalid","Tell me the complete message to send.");return;}
    String recipient=args.optString("recipient","").trim();
    if(recipient.isEmpty()||recipient.length()>100){finish(id,"invalid","Whom should I message? Give a saved name, a phone number or the app username.");return;}
    prepareMessage(id,app,recipient,text,args.optString("sim"));
  }

  private void prepareMessage(String id,String app,String recipient,String text,String sim){
    if(app.equals("sms")){
      resolveTarget(id,recipient,new Target(){
        public void use(String label,String key,String digits){
          if(digits==null){finish(id,"not_found","That contact has no usable phone number.");return;}
          storePending(id,app,label,key,digits,text,sim);
        }
      });
      return;
    }
    if(app.equals("whatsapp")){
      resolveTarget(id,recipient,new Target(){
        public void use(String label,String key,String digits){
          if(digits==null){finish(id,"not_found","That contact has no usable phone number. Give the full number with country code.");return;}
          storePending(id,app,label,"+"+digits,digits,text,sim);
        }
      });
      return;
    }
    String handle=recipient.replaceAll("^@","").trim();
    if(!handle.matches("[A-Za-z0-9._]{2,32}")){
      finish(id,"invalid","For "+appLabel(app)+" I need the exact username (for example @name), not a phone number.");
      return;
    }
    storePending(id,app,"@"+handle,handle,null,text,sim);
  }

  private interface Target { void use(String label,String key,String digits); }

  /** Accepts a phone number or resolves a saved contact name, asking when ambiguous. */
  private void resolveTarget(String id,String recipient,Target target){
    String digits=numberDigits(recipient,activity);
    if(digits!=null){target.use(recipient,recipient,digits);return;}
    if(contacts.needsPermission()&&!granted(Manifest.permission.READ_CONTACTS)){
      if(pending!=null||pendingTorchId!=null){finish(id,"busy","Finish the current permission request first.");return;}
      pendingId=id;pending=()->resolveTarget(id,recipient,target);
      activity.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_REQUEST);return;
    }
    String spoken=NameMatcher.extractSpokenName(recipient);
    matchPerson(id,spoken.isEmpty()?recipient:spoken,false,(person)->{
      if(matcher!=null)matcher.rememberAlias(spoken,person.name);
      target.use(person.name,person.name,person.phone);
    });
  }

  private interface PersonUser { void use(Recipient person); }

  /** Phonetic/fuzzy match against the address book. Never auto-calls an ambiguous name. */
  private void matchPerson(String id,String spoken,boolean calling,PersonUser user){
    if(matcher!=null){
      NameMatcher.Decision decision=matcher.match(spoken);
      if("none".equals(decision.verdict)||decision.best==null){
        finish(id,"not_found",calling
          ?"No contact with a phone number matches “"+spoken+"” closely enough. Say the full saved name, a nickname, or the number itself. Nobody was called."
          :"No contact with a phone number matches “"+spoken+"”. Give the full number with country code.");
        return;
      }
      List<Recipient> ranked=new ArrayList<>();
      for(NameMatcher.Candidate c:decision.ranked)ranked.add(new Recipient(c.name,c.phone,c.score));
      Recipient best=new Recipient(decision.best.name,decision.best.phone,decision.best.score);
      if("choose".equals(decision.verdict)){
        // The phone book cannot settle it (Baba / Baban / Baban-2 / Bablu). Instead of a long
        // list, ask for a word and offer only the few people who are genuinely close.
        if(decision.askWord)askKeyWord(id,spoken,decision,ranked,calling,user);
        else choosePerson(id,spoken,ranked,calling,user);
        return;
      }
      if("confirm".equals(decision.verdict)){confirmPerson(id,spoken,best,calling,user);return;}
      announcePerson(best,calling);
      rememberPick(spoken,decision.best);
      user.use(best);
      return;
    }
    List<Recipient> matches=contacts.find(spoken);
    if(matches.isEmpty()){
      finish(id,"not_found",calling
        ?"No contact with a phone number matches “"+spoken+"”. Say the full saved name or the number itself."
        :"No contact with a phone number matches “"+spoken+"”. Give the full number with country code.");
      return;
    }
    if(matches.size()==1){
      Recipient person=matches.get(0);
      if(person.score>=NameMatcher.AUTO_SCORE){announcePerson(person,calling);user.use(person);}
      else confirmPerson(id,spoken,person,calling,user);
      return;
    }
    choosePerson(id,spoken,matches,calling,user);
  }

  private void announcePerson(Recipient person,boolean calling){
    if(speech==null)return;
    try{speech.speak(calling?"Calling "+person.name:"Message for "+person.name);}catch(RuntimeException ignored){}
  }

  private void confirmPerson(String id,String spoken,Recipient person,boolean calling,PersonUser user){
    if(chooser!=null){finish(id,"busy",calling?"Choose the contact from the current list first.":"Choose the recipient from the current list first.");return;}
    chooserId=id;
    announcePerson(person,calling);
    chooser=new AlertDialog.Builder(activity)
      .setTitle(calling?"এই নামে কল করব?":"Send to this contact?")
      .setMessage(person.name+" · +"+person.phone+"\n\nConfirm the match. A wrong name will not be called.")
      .setPositiveButton(calling?"কল করো":"Use", (d,w)->{chooser=null;user.use(person);})
      .setNegativeButton(calling?"বাতিল":"Cancel",(d,w)->{chooser=null;finish(id,"cancelled",calling?"Call cancelled. Nobody was called.":"Cancelled. Nothing was sent.");})
      .setOnCancelListener(d->{chooser=null;finish(id,"cancelled",calling?"Call cancelled. Nobody was called.":"Cancelled. Nothing was sent.");}).show();
  }

  private void rememberRecipient(String spoken,Recipient chosen){
    if(chosen==null)return;
    rememberPick(spoken,new NameMatcher.Candidate(chosen.name,chosen.phone,chosen.score,"chosen"));
  }

  /** Saves which person this spoken word means, so the same question is never asked twice. */
  private void rememberPick(String spoken,NameMatcher.Candidate chosen){
    try{if(matcher!=null&&chosen!=null)matcher.rememberPick(spoken,chosen);}catch(RuntimeException ignored){ /* a pick that cannot be saved must not block the call */ }
  }

  /**
   * Asks for one clarifying word instead of showing every contact that starts with "ba".
   *
   * Three answers: a full name typed or said, one of the few close people, or a relation tag
   * (বাবা, মা, বোন…) which is then remembered for that contact.
   */
  private void askKeyWord(String id,String spoken,NameMatcher.Decision decision,List<Recipient> ranked,boolean calling,PersonUser user){
    if(chooser!=null){finish(id,"busy",calling?"Answer the current contact question first.":"Choose the recipient from the current question first.");return;}
    final List<NameMatcher.Candidate> close=new ArrayList<>(decision.ranked);
    String[] buttons=new String[close.size()];
    for(int i=0;i<buttons.length;i++)buttons[i]=close.get(i).name+" · +"+close.get(i).phone;
    chooserId=id;
    chooser=new AlertDialog.Builder(activity)
      .setTitle("“"+spoken+"” — কাকে?")
      .setMessage(calling
        ?"এই নামে কয়েকজন আছেন। নামটা পুরো বলুন (যেমন “Baban-2”), বা নিচের যিনি ঠিক তিনি — পরের বার আর জিজ্ঞেস করব না।"
        :"কয়েকজন মিলেছেন। নামটা পুরো বলুন, বা নিচের সঠিক জনকে বেছে নিন — পরের বার মনে রাখব।")
      .setItems(buttons,(d,index)->{
        chooser=null;
        NameMatcher.Candidate chosen=close.get(index);
        rememberPick(spoken,chosen);
        user.use(new Recipient(chosen.name,chosen.phone,chosen.score));
      })
      .setNegativeButton(calling?"বাতিল":"Cancel",(d,w)->{chooser=null;finish(id,"cancelled",calling?"Call cancelled. Nobody was called.":"Cancelled. Nothing was sent.");})
      .setNeutralButton("আরেক নাম বলব",(d,w)->{chooser=null;finish(id,"needs_name",
        calling?"Say the full saved name (for example \"Baban-2\") or the phone number, and I will call that person.":"Say the full saved name or the phone number, and I will message that person.");})
      .setOnCancelListener(d->{chooser=null;finish(id,"cancelled",calling?"Call cancelled. Nobody was called.":"Cancelled. Nothing was sent.");})
      .show();
  }

  private void choosePerson(String id,String spoken,List<Recipient> matches,boolean calling,PersonUser user){
    if(chooser!=null){finish(id,"busy",calling?"Choose the contact from the current list first.":"Choose the recipient from the current list first.");return;}
    String[] labels=new String[matches.size()];for(int i=0;i<labels.length;i++)labels[i]=matches.get(i).name+" · +"+matches.get(i).phone;
    chooserId=id;chooser=new AlertDialog.Builder(activity).setTitle(calling?"কাকে কল করবে — "+spoken+"?":"Which "+spoken+"?")
      .setItems(labels,(d,index)->{chooser=null;rememberRecipient(spoken,matches.get(index));user.use(matches.get(index));})
      .setNegativeButton(calling?"বাতিল":"Cancel",(d,w)->{chooser=null;finish(id,"cancelled",calling?"Call cancelled. Nobody was called.":"Cancelled. Nothing was sent.");})
      .setOnCancelListener(d->{chooser=null;finish(id,"cancelled",calling?"Call cancelled. Nobody was called.":"Cancelled. Nothing was sent.");}).show();
  }

  private static final String KEY_PENDING_SEND = "pendingSend";

  /** Reloads a prepared message that was saved before the process died. */
  private void restorePending() {
    try {
      String raw = JarvisPrefs.get(activity).getString(KEY_PENDING_SEND, "");
      if (raw == null || raw.isEmpty()) return;
      JSONObject stored = new JSONObject(raw);
      if (System.currentTimeMillis() - stored.optLong("at", 0) > PENDING_TTL) { clearPending(); return; }
      pendingSend = stored;
    } catch (Exception ignored) { /* tolerated: a corrupt saved draft is simply dropped */ }
  }

  private void clearPending() {
    pendingSend = null;
    JarvisPrefs.get(activity).edit().remove(KEY_PENDING_SEND).apply();
  }

  private void storePending(String id,String app,String label,String key,String digits,String text,String sim){
    pendingSend=json("app",app,"label",label,"key",key,"digits",digits==null?"":digits,"text",text,"sim",sim==null?"":sim,"at",System.currentTimeMillis());
    // Survives the process being killed between "prepare" and "send".
    JarvisPrefs.get(activity).edit().putString(KEY_PENDING_SEND,pendingSend.toString()).apply();
    String preview=text.length()>120?text.substring(0,120)+"…":text;
    finish(id,"needs_confirmation","Ready to send to "+label+" on "+appLabel(app)+": “"+preview+"”. Say “send” to confirm, or “cancel”.",
      json("pending",json("app",app,"label",label,"text",text)));
  }

  private void confirmSend(String id){
    if(pendingSend==null) restorePending();
    JSONObject pendingMessage=pendingSend;
    if(pendingMessage==null){finish(id,"nothing_pending","There is no prepared message. Tell me whom to message and what to say first.");return;}
    if(System.currentTimeMillis()-pendingMessage.optLong("at",0)>PENDING_TTL){
      clearPending();finish(id,"expired","That prepared message expired. Tell me the message again.");return;
    }
    String app=pendingMessage.optString("app"),label=pendingMessage.optString("label");
    String text=pendingMessage.optString("text"),sim=pendingMessage.optString("sim");
    String digits=pendingMessage.optString("digits"),key=pendingMessage.optString("key");
    if(app.equals("sms")){clearPending();sendSms(id,digits,label,text,sim);return;}
    if(app.equals("whatsapp")){
      String pkg=whatsapp();
      if(pkg==null){finish(id,"not_installed","WhatsApp is not installed on this phone.");return;}
      dispatchChat(id,app,pkg,label,"whatsapp://send?phone="+digits,text);return;
    }
    dispatchChat(id,app,chatPackage(app),label,chatDeepLink(app,key),text);
  }

  private String appLabel(String app){
    if(app.equals("sms"))return "SMS";
    if(app.equals("whatsapp"))return "WhatsApp";
    if(app.equals("telegram"))return "Telegram";
    if(app.equals("instagram"))return "Instagram";
    if(app.equals("messenger"))return "Messenger";
    return appLabelStatic(app);
  }

  private String chatPackage(String app){
    if(app.equals("telegram"))return launcher.installed("org.telegram.messenger")?"org.telegram.messenger":"org.telegram.plus";
    if(app.equals("instagram"))return "com.instagram.android";
    if(app.equals("messenger"))return "com.facebook.orca";
    return "";
  }

  private String chatDeepLink(String app,String handle){
    if(app.equals("telegram"))return "tg://resolve?domain="+Uri.encode(handle);
    if(app.equals("instagram"))return "https://ig.me/m/"+Uri.encode(handle);
    if(app.equals("messenger"))return "https://m.me/"+Uri.encode(handle);
    return null;
  }

  private void dispatchChat(String id,String app,String pkg,String label,String deepLink,String text){
    if(pkg==null||pkg.isEmpty()||!launcher.installed(pkg)){finish(id,"not_installed",appLabel(app)+" is not installed on this phone.");return;}
    UiAutomationService service=UiAutomationService.get();
    if(service==null){
      finish(id,"permission_required","To send directly in "+appLabel(app)+" I need the “Vishal Jarvis message sender” accessibility service. Enable it in Android Settings → Accessibility (Android may ask you to allow restricted settings first), then say “send” again.",json("needsAccessibility",true));
      return;
    }
    final String appName=appLabel(app);
    String phone = pendingSend==null?"":pendingSend.optString("digits","");
    service.send(pkg,deepLink,label,text,phone,messagesTitle(label),(status,message)->{
      if("sent".equals(status)){
        clearPending();
        finish(id,"sent","Sent to "+label+" on "+appName+".");
      }else if("sent_unverified".equals(status)){
        clearPending();
        finish(id,"sent_unverified",message);
      }else if("cancelled".equals(status)||"timeout".equals(status)||"busy".equals(status)){
        finish(id,status,message);
      }else{
        finish(id,"failed",message);
      }
    });
  }

  /** The name the chat title normally shows; WhatsApp may show the number, which is also accepted. */
  private static String messagesTitle(String label){ return label==null?"":label.trim(); }

  private void sendSms(String id,String digits,String label,String text,String sim){
    if(!granted(Manifest.permission.SEND_SMS)){requestSmsPermission(id,()->sendSms(id,digits,label,text,sim));return;}
    try{
      SmsManager manager=SmsManager.getDefault();
      int subscription=subscriptionForSim(sim);
      if(simIndex(sim)>0 && subscription<0){
        finish(id,"unavailable","Cannot select that SIM for SMS without phone-state access. Nothing was sent.");return;
      }
      if(subscription>=0){
        SmsManager picked=activity.getSystemService(SmsManager.class);
        if(picked!=null&&Build.VERSION.SDK_INT>=31)manager=picked.createForSubscriptionId(subscription);
        else if(Build.VERSION.SDK_INT>=22)manager=SmsManager.getSmsManagerForSubscriptionId(subscription);
      }
      Intent receipt=new Intent(SMS_RECEIPT).setPackage(activity.getPackageName())
        .putExtra("token",smsToken).putExtra("id",id).putExtra("to",digits);
      PendingIntent sent=PendingIntent.getBroadcast(activity,Math.abs((id+"sms").hashCode()),receipt,
        PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      ArrayList<String> parts=manager.divideMessage(text);
      if(parts.size()>1){
        ArrayList<PendingIntent> receipts=new ArrayList<>();
        for(int i=0;i<parts.size();i++)receipts.add(sent);
        manager.sendMultipartTextMessage("+"+digits,null,parts,receipts,null);
      }else{
        manager.sendTextMessage("+"+digits,null,text,sent,null);
      }
      String simNote=simIndex(sim)>0?" on SIM "+simIndex(sim):"";
      finish(id,"sms_queued","SMS to "+label+simNote+" handed to Android to send.");
    }catch(SecurityException e){
      finish(id,"permission_required","Allow the SMS permission for Vishal Jarvis so it can send messages.");
    }catch(RuntimeException e){
      finish(id,"failed","Android refused the SMS. Nothing was sent.");
    }
  }

  private int subscriptionForSim(String sim){
    int index=simIndex(sim);
    if(index<1||!hasPermission(Manifest.permission.READ_PHONE_STATE))return -1;
    try{
      SubscriptionManager manager=(SubscriptionManager)activity.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
      if(manager==null)return -1;
      List<SubscriptionInfo> active=manager.getActiveSubscriptionInfoList();
      if(active==null||active.size()<index)return -1;
      return active.get(index-1).getSubscriptionId();
    }catch(RuntimeException ignored){return -1;}
  }

  private void cancelSend(String id){
    if(pendingSend==null)restorePending();
    boolean had=pendingSend!=null;
    clearPending();
    if(UiAutomationService.get()!=null)UiAutomationService.get().cancel("Sending cancelled.");
    finish(id,had?"cancelled":"nothing_pending",had?"Cancelled. Nothing was sent.":"There was nothing waiting to send.");
  }
  public void cancelPendingSend(){clearPending();}

  // ---------------------------------------------------------------- other actions

  private void openUrl(String id,String url){
    Intent intent=web(url);boolean opened=launcher.open(intent);
    finish(id,opened?"opened":"unavailable",opened?"Opened the link in your browser.":"No browser could open that link.");
  }
  private void playMedia(String id,JSONObject args){
    String query=limit(args.optString("query"),200,""),app=normalize(args.optString("app"));
    if(query.isEmpty()){finish(id,"invalid","Say the song, video or channel to play, for example “play Millionaire on Spotify”.");return;}
    if(!app.isEmpty()&&!PLAYERS.containsKey(app)){
      finish(id,"invalid","Music apps I can start: "+playerNames()+". Say one of them, or just name a song and I will use the best match.");return;
    }
    if(app.isEmpty()){
      for(String candidate:PLAYER_ORDER){
        if(!launcher.installed(PLAYERS.get(candidate)))continue;
        if(play(id,candidate,query))return;
      }
      Intent webSearch=search("youtube",query);
      if(launcher.installed("com.google.android.youtube"))webSearch.setPackage("com.google.android.youtube");
      boolean opened=launcher.open(webSearch);
      finish(id,opened?"search_opened":"unavailable",opened
        ?"No music app could start directly, so I opened a YouTube search for “"+query+"”. Install Spotify, YouTube Music, Prime Music or Gaana for direct playback."
        :"No music app is installed and no browser could open a search.",json("guidance","install_music_app"));
      return;
    }
    if(!launcher.installed(PLAYERS.get(app))){
      finish(id,"not_installed",appLabel(app)+" is not installed on this phone.",json("guidance","install_music_app"));
      return;
    }
    play(id,app,query);
  }
  /** True when a player accepted the query. Reports a direct start and a search-screen open differently. */
  private boolean play(String id,String app,String query){
    Intent browse=browseIntent(app,query);
    if(browse!=null&&launcher.open(browse)){
      finish(id,"play_started","Asked "+appLabel(app)+" to play “"+query+"”."+playHint(app),json("player",appLabel(app),"query",query,"mode","direct"));
      return true;
    }
    Intent fallback=searchScreen(app,query);
    if(fallback!=null&&launcher.open(fallback)){
      finish(id,"play_started","Opened "+appLabel(app)+" with a search for “"+query+"”. If a list appears, tap the song or video to start it.",json("player",appLabel(app),"query",query,"mode","search"));
      return true;
    }
    return false;
  }
  private static String playHint(String app){
    if(app.equals("spotify"))return " If nothing starts, open Spotify once and sign in, then ask again.";
    if(app.equals("gaana")||app.equals("jiosaavn"))return " If nothing starts, open the app once and try again.";
    return "";
  }
  /** Android's standard media-browse intent, pinned to one player. */
  private Intent browseIntent(String app,String query){
    Intent browse=new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
      .putExtra(MediaStore.EXTRA_MEDIA_FOCUS,MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
      .putExtra(MediaStore.EXTRA_MEDIA_TITLE,query)
      .putExtra(SearchManager.QUERY,query);
    String pkg=PLAYERS.get(app);
    if(pkg!=null&&!pkg.isEmpty())browse.setPackage(pkg);
    return browse.resolveActivity(activity.getPackageManager())==null?null:browse;
  }
  /** The player's own search screen, used only when it cannot start playback directly. */
  private Intent searchScreen(String app,String query){
    String encoded=Uri.encode(query);
    if(app.equals("spotify"))return new Intent(Intent.ACTION_VIEW,Uri.parse("https://open.spotify.com/search/"+encoded)).setPackage(PLAYERS.get(app));
    if(app.equals("youtube_music"))return new Intent(Intent.ACTION_VIEW,Uri.parse("https://music.youtube.com/search?q="+encoded)).setPackage(PLAYERS.get(app));
    if(app.equals("youtube"))return new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com/results").buildUpon().appendQueryParameter("search_query",query).build()).setPackage(PLAYERS.get(app));
    if(app.equals("gaana"))return new Intent(Intent.ACTION_VIEW,Uri.parse("https://gaana.com/search/"+encoded)).setPackage(PLAYERS.get(app));
    if(app.equals("jiosaavn"))return new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.jiosaavn.com/search/"+encoded)).setPackage(PLAYERS.get(app));
    return null;
  }
  private static String playerNames(){
    StringBuilder names=new StringBuilder();
    for(String key:PLAYER_ORDER){if(names.length()>0)names.append(", ");names.append(appLabelStatic(key));}
    return names.toString();
  }
  private static String appLabelStatic(String app){
    if(app.equals("youtube"))return "YouTube";
    if(app.equals("youtube_music"))return "YouTube Music";
    if(app.equals("spotify"))return "Spotify";
    if(app.equals("prime_music"))return "Amazon Prime Music";
    if(app.equals("gaana"))return "Gaana";
    if(app.equals("jiosaavn"))return "JioSaavn";
    return app;
  }
  private void openCamera(String id){
    boolean opened=launcher.open(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
    finish(id,opened?"opened":"unavailable",opened?"Camera opened.":"This device has no camera app available.");
  }
  private void setFlashlight(String id,boolean enabled){
    if(!granted(Manifest.permission.CAMERA)){
      if(pending!=null||pendingTorchId!=null){finish(id,"busy","Finish the current permission request first.");return;}
      pendingTorchId=id;pendingTorchState=enabled;activity.requestPermissions(new String[]{Manifest.permission.CAMERA},TORCH_REQUEST);return;
    }
    applyFlashlight(id,enabled);
  }
  private void applyFlashlight(String id,boolean enabled){
    try{
      CameraManager manager=(CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);String cameraId=null;
      for(String candidate:manager.getCameraIdList())if(manager.getCameraCharacteristics(candidate).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)==Boolean.TRUE){cameraId=candidate;break;}
      if(cameraId==null){finish(id,"unavailable","এই ফোনে Flashlight হার্ডওয়্যার পাওয়া যায়নি।");return;}
      manager.setTorchMode(cameraId,enabled);finish(id,"torch_changed",enabled?"Flashlight অন করেছি।":"Flashlight বন্ধ করেছি।");
    }catch(SecurityException e){finish(id,"permission_required","Flashlight ব্যবহারের জন্য Camera permission দিতে হবে।");}
    catch(Exception e){finish(id,"failed","Flashlight পরিবর্তন করা যায়নি। অন্য কোনো অ্যাপ ক্যামেরা ব্যবহার করছে কি না দেখো।");}
  }
  /**
   * Turns the call assistant on or off and reports what it will really do.
   *
   * Android does not let an app flip an accessibility or default-phone switch, so "status" is the
   * honest answer when the user only asks whether it is on.
   */
  private void callAssistant(String id,JSONObject args){
    String wanted=normalize(args.optString("command","status"));
    CallCoach.Mode mode=JarvisPrefs.callMode(activity);
    if(wanted.equals("off")||wanted.equals("stop")||wanted.equals("disable")){
      JarvisPrefs.get(activity).edit().putString(JarvisPrefs.CALL_ASSIST,"off").apply();
      CallAssistService.stop(activity);
      finish(id,"call_assistant_changed","Call assistant off. Nothing will listen during calls.");
      return;
    }
    if(wanted.equals("on")||wanted.equals("start")||wanted.equals("enable")||wanted.equals("suggest")||wanted.equals("speak")){
      String value=wanted.equals("speak")?"speak":(mode==CallCoach.Mode.OFF?"suggest":mode.key);
      JarvisPrefs.get(activity).edit().putString(JarvisPrefs.CALL_ASSIST,value).apply();
      if(granted(Manifest.permission.RECORD_AUDIO))CallAssistService.start(activity);
      boolean overlay=Settings.canDrawOverlays(activity);
      StringBuilder message=new StringBuilder("Call assistant ").append(value.equals("speak")?"on: it will speak the suggested answer into the call.":"on: it will suggest answers on the card.");
      if(!granted(Manifest.permission.RECORD_AUDIO))message.append(" Allow the Microphone permission so it can hear the call.");
      if(!overlay)message.append(" Allow \"display over other apps\" so the call card can appear.");
      if(!JarvisPrefs.callAutoAnswer(activity))message.append(" Turn on \"Answer incoming calls automatically\" in Jarvis Pro settings if it should pick up ringing calls.");
      finish(id,"call_assistant_changed",message.toString());
      return;
    }
    boolean speaking=mode==CallCoach.Mode.SPEAK;
    finish(id,"call_assistant",CallAssistService.describeMode(activity)
      +". Microphone "+(granted(Manifest.permission.RECORD_AUDIO)?"allowed":"not allowed")
      +", call card "+(Settings.canDrawOverlays(activity)?"allowed":"not allowed")
      +", auto answer "+(JarvisPrefs.callAutoAnswer(activity)?"on":"off")
      +", speaking "+(speaking?"on":"off")+".");
  }
  private void openWifiSettings(String id){
    Intent intent=Build.VERSION.SDK_INT>=29?new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY):new Intent(Settings.ACTION_WIFI_SETTINGS);
    boolean opened=launcher.open(intent);if(!opened&&Build.VERSION.SDK_INT>=29)opened=launcher.open(new Intent(Settings.ACTION_WIFI_SETTINGS));finish(id,opened?"settings_opened":"unavailable",opened?"Wi-Fi ও Internet প্যানেল খুলেছি। Android-এর নিয়মে এখানে Wi-Fi সুইচটি তোমাকেই অন করতে হবে।":"এই ফোনে Wi-Fi সেটিংস খোলা যায়নি।");
  }
  private void openPanel(String id,String raw){
    String panel=normalize(raw);
    Intent intent;
    if(panel.equals("bluetooth"))intent=new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
    else if(panel.equals("sound"))intent=new Intent(Settings.ACTION_SOUND_SETTINGS);
    else if(panel.equals("display"))intent=new Intent(Settings.ACTION_DISPLAY_SETTINGS);
    else if(panel.equals("battery"))intent=new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
    else if(panel.equals("location"))intent=new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
    else if(panel.equals("apps"))intent=new Intent(Settings.ACTION_APPLICATION_SETTINGS);
    else if(panel.equals("notifications"))intent=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.getPackageName());
    else if(panel.equals("data"))intent=new Intent(Settings.ACTION_DATA_USAGE_SETTINGS);
    else if(panel.equals("airplane"))intent=new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS);
    else if(panel.equals("accessibility"))intent=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    else if(panel.equals("nfc"))intent=new Intent(Settings.ACTION_NFC_SETTINGS);
    else if(panel.equals("wifi")){openWifiSettings(id);return;}
    else if(panel.equals("home"))intent=new Intent(Settings.ACTION_HOME_SETTINGS);
    else intent=new Intent(Settings.ACTION_SETTINGS);
    boolean opened=launcher.open(intent);
    finish(id,opened?"settings_opened":"unavailable",opened?"Opened the "+panel+" settings. Android does not let this app change that switch silently, so tap the control yourself.":"This device could not open those settings.");
  }
  private void setAlarm(String id,int hour,int minute,String label){
    if(hour<0||hour>23||minute<0||minute>59){finish(id,"invalid","Say the alarm time on a 24-hour clock, for example 07:30.");return;}
    Intent intent=new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,hour).putExtra(AlarmClock.EXTRA_MINUTES,minute)
      .putExtra(AlarmClock.EXTRA_SKIP_UI,false).putExtra(AlarmClock.EXTRA_MESSAGE,limit(label,80,"Vishal Jarvis"));
    boolean opened=launcher.open(intent);
    finish(id,opened?"alarm_opened":"unavailable",opened?String.format(Locale.US,"Alarm editor opened for %02d:%02d. Confirm it in your clock app.",hour,minute):"This device has no alarm app that accepts new alarms.");
  }
  private void setTimer(String id,int seconds,String label){
    if(seconds<1||seconds>86400){finish(id,"invalid","Say a timer between 1 second and 24 hours.");return;}
    Intent intent=new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH,seconds)
      .putExtra(AlarmClock.EXTRA_SKIP_UI,false).putExtra(AlarmClock.EXTRA_MESSAGE,limit(label,80,"Vishal Jarvis"));
    boolean opened=launcher.open(intent);
    finish(id,opened?"timer_opened":"unavailable",opened?"Timer editor opened for "+seconds+" seconds. Confirm it in your clock app.":"This device has no timer app that accepts new timers.");
  }
  private void createEvent(String id,JSONObject args){
    String title=limit(args.optString("title"),200,"");
    if(title.isEmpty()){finish(id,"invalid","Say the event title.");return;}
    long begin=args.optLong("beginTime",0),end=args.optLong("endTime",0);
    long now=System.currentTimeMillis();
    if(begin<=0){finish(id,"invalid","Give the event start as a Unix timestamp in milliseconds.");return;}
    if(begin<now-366L*24*3600*1000||begin>now+5L*366*24*3600*1000){finish(id,"invalid","That event date is outside the supported range.");return;}
    if(end<=begin)end=begin+3600000L;
    if(end-begin>7L*24*3600*1000){finish(id,"invalid","Events longer than seven days are not supported.");return;}
    Intent intent=new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
      .putExtra(CalendarContract.Events.TITLE,title).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,begin)
      .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,end).putExtra(CalendarContract.Events.EVENT_LOCATION,limit(args.optString("location"),200,""))
      .putExtra(CalendarContract.Events.DESCRIPTION,limit(args.optString("description"),1000,""));
    boolean opened=launcher.open(intent);
    finish(id,opened?"event_opened":"unavailable",opened?"Calendar editor opened. Review the event and tap Save; nothing was added yet.":"This device has no calendar app that accepts new events.");
  }
  private void navigate(String id,String destination){
    String target=limit(destination,300,"");
    if(target.isEmpty()){finish(id,"invalid","Say where you want to go.");return;}
    Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(target)));
    boolean opened=launcher.open(intent);
    if(!opened){opened=launcher.open(web("https://www.google.com/maps/search/?api=1&query="+Uri.encode(target)));}
    finish(id,opened?"navigated":"unavailable",opened?"Maps opened for "+target+". Start navigation when you are ready.":"No maps app or browser could handle that destination.");
  }
  private void share(String id,String text){
    String body=limit(text,4000,"");
    if(body.isEmpty()){finish(id,"invalid","There is nothing to share.");return;}
    Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,body);
    boolean opened=launcher.open(Intent.createChooser(send,"Share with"));
    finish(id,opened?"shared":"unavailable",opened?"Android share sheet opened. Choose the app yourself.":"No app can share text on this device.");
  }
  private void copy(String id,String text){
    String body=limit(text,8000,"");
    if(body.isEmpty()){finish(id,"invalid","There is nothing to copy.");return;}
    try{
      ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
      clipboard.setPrimaryClip(ClipData.newPlainText("Vishal Jarvis",body));
      finish(id,"copied","Copied to the clipboard.");
    }catch(RuntimeException e){finish(id,"failed","Android blocked clipboard access.");}
  }
  private void media(String id,String command){
    String value=normalize(command);int key;
    if(value.equals("play_pause")||value.equals("play")||value.equals("pause")||value.equals("toggle"))key=KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
    else if(value.equals("next"))key=KeyEvent.KEYCODE_MEDIA_NEXT;
    else if(value.equals("previous")||value.equals("prev"))key=KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    else {finish(id,"invalid","Media command must be play_pause, next or previous.");return;}
    try{
      AudioManager audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
      long now=SystemClock.uptimeMillis();
      audio.dispatchMediaKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,key,0));
      audio.dispatchMediaKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,key,0));
      finish(id,"media_sent","Sent the media "+value.replace('_',' ')+" key. If nothing changes, start playback in your music app first.");
    }catch(RuntimeException e){finish(id,"failed","Android blocked the media key. Nothing was changed.");}
  }
  private void volume(String id,JSONObject args){
    try{
      AudioManager audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
      int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),current=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
      int target=current;
      if(args.has("level")){
        double percent=args.optDouble("level",-1);if(percent<0||percent>100){finish(id,"invalid","Volume level must be between 0 and 100.");return;}
        target=(int)Math.round(max*percent/100.0);
      }else if(args.has("direction")){
        String direction=normalize(args.optString("direction"));
        int step=Math.max(1,max/10);target=current+(direction.equals("down")||direction.equals("lower")?-step:step);
      }else{finish(id,"invalid","Say the volume level or whether to raise or lower it.");return;}
      target=Math.max(0,Math.min(max,target));
      audio.setStreamVolume(AudioManager.STREAM_MUSIC,target,0);
      int percent=max==0?0:(int)Math.round(target*100.0/max);
      finish(id,"volume_changed","Media volume is now "+percent+"%.",json("level",percent));
    }catch(RuntimeException e){finish(id,"failed","Android blocked the volume change. Do Not Disturb may be limiting it.");}
  }
  private void deviceStatus(String id){
    JSONObject data=new JSONObject();
    try{
      Intent battery=activity.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      int level=battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
      int scale=battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
      int state=battery==null?0:battery.getIntExtra(BatteryManager.EXTRA_STATUS,BatteryManager.BATTERY_STATUS_UNKNOWN);
      int percent=level>=0&&scale>0?(int)Math.round(level*100.0/scale):-1;
      data.put("batteryPercent",percent);
      data.put("charging",state==BatteryManager.BATTERY_STATUS_CHARGING||state==BatteryManager.BATTERY_STATUS_FULL);
      ConnectivityManager cm=(ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);
      Network active=cm==null?null:cm.getActiveNetwork();NetworkCapabilities capabilities=active==null?null:cm.getNetworkCapabilities(active);
      String network="offline";
      if(capabilities!=null){
        if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))network="wifi";
        else if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))network="mobile data";
        else if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))network="ethernet";
        else if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))network="vpn";
        else network="connected";
      }
      data.put("network",network);
      StatFs storage=new StatFs(activity.getFilesDir().getAbsolutePath());
      data.put("freeStorageMb",storage.getAvailableBytes()/(1024*1024));
      AudioManager audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
      int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      data.put("mediaVolumePercent",max==0?0:(int)Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC)*100.0/max));
      data.put("sims",callAccounts().size());
      data.put("device",Build.MANUFACTURER+" "+Build.MODEL);
      data.put("android",Build.VERSION.RELEASE);
      data.put("sdk",Build.VERSION.SDK_INT);
      data.put("appVersion",BuildInfo.VERSION);
      StringBuilder summary=new StringBuilder();
      summary.append("Battery ").append(percent<0?"unknown":percent+"%").append(data.optBoolean("charging")?", charging":"").append(". ");
      summary.append("Network: ").append(network).append(". ");
      summary.append("Free storage: ").append(data.opt("freeStorageMb")).append(" MB. ");
      summary.append("Media volume: ").append(data.opt("mediaVolumePercent")).append("%. ");
      summary.append("SIMs: ").append(callAccounts().size()).append(". ");
      summary.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append('.');
      finish(id,"info",summary.toString(),data);
    }catch(Exception e){finish(id,"failed","Android did not return device status.");}
  }
  private void listApps(String id){
    try{
      PackageManager pm=activity.getPackageManager();
      Intent main=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
      Set<String> seen=new TreeSet<>();
      for(ResolveInfo info:pm.queryIntentActivities(main,0)){
        if(!info.activityInfo.exported)continue;
        String label=normalize(info.loadLabel(pm).toString());
        if(!label.isEmpty())seen.add(label);
        if(seen.size()>=MAX_APPS)break;
      }
      JSONArray apps=new JSONArray();
      for(String label:seen)apps.put(label);
      int count=apps.length();
      finish(id,"apps","Found "+count+" launchable apps on this device.",json("apps",apps));
    }catch(Exception e){finish(id,"failed","Android did not return the installed app list.");}
  }
  private static String limit(String value,int max,String fallback){
    String trimmed=value==null?"":value.trim();
    if(trimmed.isEmpty())return fallback;
    return trimmed.length()>max?trimmed.substring(0,max):trimmed;
  }
  /** "ইউটিউব খোলো", "the YouTube app" and "my calculator" all become the launcher label. */
  static String appQuery(String raw){
    String name=normalize(raw).replaceAll("^[\"'“”]|[\"'“”]$","").replaceAll("\\s+"," ").trim();
    for(String[] alias:APP_ALIASES)if(normalize(alias[0]).equals(name))return alias[1];
    name=name.replaceAll("^(?:the|a|an|my)\\s+","").replaceAll("\\s+(?:app|application)$","").replaceAll("\\s+"," ").trim();
    for(String[] alias:APP_ALIASES)if(normalize(alias[0]).equals(name))return alias[1];
    return name;
  }

  private void openApp(String id,String input){
    String raw=limit(input,100,"");
    if(raw.isEmpty()){finish(id,"invalid","Say which app to open, for example “open YouTube”.");return;}
    String app=appQuery(raw);
    if(app.isEmpty()||app.matches("(?iu)it|that|this|app|the app")){finish(id,"invalid","Say the app's name as it appears on your home screen, for example YouTube, WhatsApp or Calculator.");return;}
    Intent intent=null;
    if(Arrays.asList("wp","wa","whats app","whatsapp","व्हाट्सएप").contains(app)){
      String pkg=whatsapp();if(pkg==null){finish(id,"not_installed","WhatsApp is not installed on this Android device.");return;}
      intent=activity.getPackageManager().getLaunchIntentForPackage(pkg);
      if(intent==null)intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/")).setPackage(pkg);
    }else if(Arrays.asList("youtube","you tube","यूट्यूब").contains(app)){
      intent=youtubeHome();
    }else if(Arrays.asList("chrome","google chrome","browser","क्रोम").contains(app)){
      intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com")).setPackage(launcher.installed("com.android.chrome")?"com.android.chrome":null);
    }else if(app.equals("camera")||app.equals("कैमरा")){
      intent=new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
    }else{
      intent=launcher.app(app);
      if(intent==null)intent=launcher.app(app+"s");
      if(intent==null)intent=systemApp(app);
      if(intent==null)intent=webApp(app);
      if(intent==null){
        String suggestions=closestApps(app);
        finish(id,"not_installed",suggestions.isEmpty()
          ?"No installed app matches “"+raw+"”. Say the exact name shown under its icon."
          :"No installed app matches “"+raw+"”. Did you mean "+suggestions+"?",json("suggestions",suggestions));
        return;
      }
    }
    boolean opened=launcher.open(intent);
    // Some devices have no STILL_IMAGE_CAMERA handler; the capture intent usually still opens.
    if(!opened&&"camera".equals(app))opened=launcher.open(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
    finish(id,opened?"opened":"unavailable",opened?"Opened "+raw+".":"Android refused to open “"+raw+"”. Try again, or open it from your home screen.",json("app",app));
  }

  /** YouTube home that still works when the app is missing (browser fallback). */
  private Intent youtubeHome(){
    if(launcher.installed("com.google.android.youtube")){
      Intent app=activity.getPackageManager().getLaunchIntentForPackage("com.google.android.youtube");
      if(app!=null)return app;
      return new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com")).setPackage("com.google.android.youtube");
    }
    if(launcher.installed("com.android.chrome"))return new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com")).setPackage("com.android.chrome");
    return new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com"));
  }

  /** Popular services that can still open in the browser when their app is absent. */
  private Intent webApp(String app){
    String site=null;
    if(app.equals("facebook"))site="https://www.facebook.com";
    else if(app.equals("instagram"))site="https://www.instagram.com";
    else if(app.equals("spotify"))site="https://open.spotify.com";
    else if(app.equals("twitter")||app.equals("x"))site="https://x.com";
    else if(app.equals("threads"))site="https://www.threads.net";
    else if(app.equals("snapchat"))site="https://www.snapchat.com";
    else if(app.equals("netflix"))site="https://www.netflix.com";
    else if(app.equals("amazon"))site="https://www.amazon.in";
    else if(app.equals("flipkart"))site="https://www.flipkart.com";
    else if(app.equals("reddit"))site="https://www.reddit.com";
    else if(app.equals("linkedin"))site="https://www.linkedin.com";
    else return null;
    Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(site));
    if(launcher.installed("com.android.chrome"))intent.setPackage("com.android.chrome");
    return intent;
  }

  /** Up to three installed labels close to what the user said, for an honest "did you mean". */
  private String closestApps(String app){
    try{
      PackageManager pm=activity.getPackageManager();
      Intent main=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
      String wanted=normalize(app);
      List<String> scored=new ArrayList<>();
      for(ResolveInfo info:pm.queryIntentActivities(main,0)){
        if(!info.activityInfo.exported)continue;
        String label=info.loadLabel(pm).toString();
        String low=normalize(label);
        if(low.isEmpty())continue;
        if(low.startsWith(wanted)||low.contains(wanted)||wanted.contains(low))scored.add(label);
        if(scored.size()>=3)break;
      }
      StringBuilder out=new StringBuilder();
      for(String label:scored){if(out.length()>0)out.append(", ");out.append(label);}
      return out.toString();
    }catch(RuntimeException ignored){return "";}
  }

  private Intent systemApp(String app){
    if(app.equals("phone")||app.equals("dialer")||app.equals("calls"))return new Intent(Intent.ACTION_DIAL);
    if(app.equals("settings")||app.equals("system settings"))return new Intent(Settings.ACTION_SETTINGS);
    if(app.equals("camera"))return new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
    if(app.equals("clock")||app.equals("alarm")||app.equals("alarms"))return new Intent(AlarmClock.ACTION_SHOW_ALARMS);
    String category=null;
    if(app.contains("calendar"))category=Intent.CATEGORY_APP_CALENDAR;
    else if(app.contains("calculator"))category=Intent.CATEGORY_APP_CALCULATOR;
    else if(app.contains("contact"))category=Intent.CATEGORY_APP_CONTACTS;
    else if(app.contains("email")||app.contains("gmail")||app.contains("mail"))category=Intent.CATEGORY_APP_EMAIL;
    else if(app.contains("gallery")||app.contains("photos"))category=Intent.CATEGORY_APP_GALLERY;
    else if(app.contains("map"))category=Intent.CATEGORY_APP_MAPS;
    else if(app.contains("message")||app.contains("sms"))category=Intent.CATEGORY_APP_MESSAGING;
    else if(app.contains("music")||app.contains("song"))category=Intent.CATEGORY_APP_MUSIC;
    else if(app.contains("store")||app.contains("play store"))category=Intent.CATEGORY_APP_MARKET;
    else if(app.contains("file"))category=Intent.CATEGORY_APP_FILES;
    else if(app.contains("browser"))category=Intent.CATEGORY_APP_BROWSER;
    else return null;
    Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(category);
    return activity.getPackageManager().resolveActivity(intent,0)==null?null:intent;
  }
  private String whatsapp(){return launcher.installed("com.whatsapp")?"com.whatsapp":launcher.installed("com.whatsapp.w4b")?"com.whatsapp.w4b":null;}

  // ---------------------------------------------------------------- permissions / lifecycle

  public void permissionResult(){
    Runnable retry=pending;String id=pendingId;pending=null;pendingId=null;if(retry==null)return;
    if(granted(Manifest.permission.READ_CONTACTS))retry.run();
    else finish(id,"permission_required","Contacts access was denied. Allow Contacts permission in Android app settings, then try again.");
  }
  public void phonePermissionResult(){
    Runnable retry=pending;String id=pendingId;String permission=pendingPhonePermission;pending=null;pendingId=null;pendingPhonePermission=null;if(retry==null)return;
    if(permission!=null && granted(permission))retry.run();
    else finish(id,"permission_required","The requested Phone or SMS permission was not granted. Nothing was sent or called.");
  }
  /** The calendar delete path keeps its own pending request inside ProActions. */
  public void calendarPermissionResult(){if(proActions!=null)proActions.calendarPermissionResult();}
  public void torchPermissionResult(){
    String id=pendingTorchId;Boolean enabled=pendingTorchState;pendingTorchId=null;pendingTorchState=null;
    if(id==null||enabled==null)return;
    if(granted(Manifest.permission.CAMERA))applyFlashlight(id,enabled);
    else finish(id,"permission_required","Flashlight ব্যবহারের জন্য Camera permission অনুমতি দাও।");
  }
  public void cancel(String id){
    if(id==null||!id.matches("[A-Za-z0-9-]{1,80}"))return;
    if(completed.containsKey(id))return;
    if(id.equals(pendingId)){pending=null;pendingId=null;pendingPhonePermission=null;}
    if(id.equals(pendingTorchId)){pendingTorchId=null;pendingTorchState=null;}
    if(id.equals(chooserId)&&chooser!=null){chooser.dismiss();chooser=null;chooserId=null;}
    finish(id,"cancelled","Action cancelled. Nothing was sent.");
  }
  public void close(){
    if(proActions!=null)proActions.close();
    pending=null;pendingId=null;pendingPhonePermission=null;pendingTorchId=null;pendingTorchState=null;
    if(chooser!=null){chooser.dismiss();chooser=null;}
    if(UiAutomationService.get()!=null)UiAutomationService.get().cancel("App closed. Nothing was sent.");
    if(smsReceiverRegistered){try{activity.unregisterReceiver(smsReceiver);}catch(RuntimeException ignored){}smsReceiverRegistered=false;}
  }
}
