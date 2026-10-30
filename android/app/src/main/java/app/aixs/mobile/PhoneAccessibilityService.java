package app.aixs.mobile;

import android.accessibilityservice.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.accessibility.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Enabled exclusively by the user in Android accessibility settings. */
public final class PhoneAccessibilityService extends AccessibilityService {
    static volatile PhoneAccessibilityService active;
    private final Map<String,AccessibilityNodeInfo> nodes=new HashMap<>();
    private long generation;
    @Override protected void onServiceConnected(){active=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){active=null;clearNodes();super.onDestroy();}
    private void clearNodes(){for(AccessibilityNodeInfo n:nodes.values())n.recycle();nodes.clear();}
    private String shortText(CharSequence text){if(text==null)return "";String value=text.toString();return value.length()>5000?value.substring(0,5000):value;}
    private JSONObject node(AccessibilityNodeInfo n,int depth)throws Exception{
        if(n==null)return null;if(depth>35||nodes.size()>=1500){n.recycle();return null;}
        String id=generation+":"+nodes.size();nodes.put(id,n);Rect bounds=new Rect();n.getBoundsInScreen(bounds);
        JSONObject value=new JSONObject().put("id",id).put("package",String.valueOf(n.getPackageName())).put("class",String.valueOf(n.getClassName()))
            .put("text",n.isPassword()?"[密码已隐藏]":shortText(n.getText())).put("description",n.isPassword()?"":shortText(n.getContentDescription()))
            .put("resourceId",n.getViewIdResourceName()).put("clickable",n.isClickable()).put("editable",n.isEditable()).put("visible",n.isVisibleToUser()).put("enabled",n.isEnabled())
            .put("bounds",new JSONArray(Arrays.asList(bounds.left,bounds.top,bounds.right,bounds.bottom)));
        JSONArray children=new JSONArray();for(int i=0;i<n.getChildCount();i++){JSONObject child=node(n.getChild(i),depth+1);if(child!=null)children.put(child);}return value.put("children",children);
    }
    private Object onMain(Callable<Object> action)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())return action.call();
        FutureTask<Object> task=new FutureTask<>(action);new Handler(Looper.getMainLooper()).post(task);return task.get(8,TimeUnit.SECONDS);
    }
    Object tree()throws Exception{return onMain(()->{clearNodes();generation++;JSONArray windows=new JSONArray();for(AccessibilityWindowInfo w:getWindows()){JSONObject root=node(w.getRoot(),0);if(root!=null)windows.put(new JSONObject().put("type",w.getType()).put("root",root));w.recycle();}if(windows.length()==0){JSONObject root=node(getRootInActiveWindow(),0);if(root!=null)windows.put(new JSONObject().put("root",root));}return new JSONObject().put("windows",windows);});}
    Object action(String name,JSONObject args)throws Exception{
        if(name.equals("phone_screenshot"))return screenshot();
        return onMain(()->{
            if(name.equals("phone_global_action")){String key=args.getString("action");int value=key.equals("back")?GLOBAL_ACTION_BACK:key.equals("home")?GLOBAL_ACTION_HOME:key.equals("recents")?GLOBAL_ACTION_RECENTS:key.equals("notifications")?GLOBAL_ACTION_NOTIFICATIONS:0;if(value==0)throw new IllegalArgumentException("不支持的全局动作");return new JSONObject().put("ok",performGlobalAction(value));}
            if(name.equals("phone_tap")||name.equals("phone_swipe")){
                android.util.DisplayMetrics display=getResources().getDisplayMetrics();float x=(float)args.getDouble("x"),y=(float)args.getDouble("y"),endX=(float)args.optDouble("endX",x),endY=(float)args.optDouble("endY",y);
                if(x<0||y<0||endX<0||endY<0||x>=display.widthPixels||endX>=display.widthPixels||y>=display.heightPixels||endY>=display.heightPixels)throw new IllegalArgumentException("坐标超出屏幕");
                Path path=new Path();path.moveTo(x,y);if(name.equals("phone_swipe"))path.lineTo(endX,endY);
                GestureDescription gesture=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,Math.max(50,Math.min(2000,args.optLong("durationMs",name.equals("phone_tap")?80:400))))).build();
                return new JSONObject().put("accepted",dispatchGesture(gesture,null,null));
            }
            AccessibilityNodeInfo n=nodes.get(args.getString("nodeId"));if(n==null)throw new IllegalArgumentException("节点已失效，请重新读取屏幕");if(!n.refresh())throw new IllegalArgumentException("屏幕已变化，请重新读取");
            if(name.equals("phone_input")){Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,args.getString("text"));return new JSONObject().put("ok",n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value));}
            int action=name.equals("phone_scroll")?(args.optString("direction","forward").equals("backward")?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD):AccessibilityNodeInfo.ACTION_CLICK;
            return new JSONObject().put("ok",n.performAction(action));
        });
    }
    private JSONObject screenshot()throws Exception{
        if(Build.VERSION.SDK_INT<30)throw new IllegalStateException("系统截图需要 Android 11 或以上");
        CountDownLatch ready=new CountDownLatch(1);AtomicReference<JSONObject> result=new AtomicReference<>();AtomicReference<Exception> error=new AtomicReference<>();
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            public void onSuccess(ScreenshotResult shot){try{Bitmap hardware=Bitmap.wrapHardwareBuffer(shot.getHardwareBuffer(),shot.getColorSpace());if(hardware==null)throw new IllegalStateException("无法读取截图");Bitmap bitmap=hardware.copy(Bitmap.Config.ARGB_8888,false);ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);result.set(new JSONObject().put("type","image").put("mimeType","image/png").put("data",android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP)));bitmap.recycle();hardware.recycle();}catch(Exception e){error.set(e);}finally{shot.getHardwareBuffer().close();ready.countDown();}}
            public void onFailure(int code){error.set(new IllegalStateException("系统拒绝截图，错误码 "+code));ready.countDown();}
        });
        if(!ready.await(8,TimeUnit.SECONDS))throw new IllegalStateException("截图超时");if(error.get()!=null)throw error.get();return result.get();
    }
}
