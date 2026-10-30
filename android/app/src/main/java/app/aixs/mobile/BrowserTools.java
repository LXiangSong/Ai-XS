package app.aixs.mobile;

import android.app.Activity;
import android.graphics.*;
import android.webkit.WebView;
import java.lang.ref.WeakReference;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import org.json.*;

/** Development tools access only WebViews owned by Ai-XS, never another app's DOM. */
final class BrowserTools {
    private static WeakReference<Activity> owner=new WeakReference<>(null);
    private static WeakReference<WebView> target=new WeakReference<>(null);
    static void attach(Activity activity,WebView web){owner=new WeakReference<>(activity);target=new WeakReference<>(web);}
    static void detach(Activity activity){if(owner.get()==activity){owner.clear();target.clear();}}
    static Object call(String name,JSONObject args)throws Exception{
        Activity activity=owner.get();WebView web=target.get();if(activity==null||web==null)throw new IllegalStateException("请先打开工作台或开发浏览器页面");
        CompletableFuture<Object> result=new CompletableFuture<>();activity.runOnUiThread(()->{try{
            if(name.equals("browser_screenshot")){if(web.getWidth()<1||web.getHeight()<1)throw new IllegalStateException("页面不可见");Bitmap bitmap=Bitmap.createBitmap(web.getWidth(),web.getHeight(),Bitmap.Config.ARGB_8888);web.draw(new Canvas(bitmap));ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();result.complete(new JSONObject().put("type","image").put("mimeType","image/png").put("data",android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP)));return;}
            String script;
            if(name.equals("browser_evaluate"))script=args.getString("script");
            else script="JSON.stringify({url:location.origin+location.pathname,title:document.title,viewport:{width:innerWidth,height:innerHeight},html:document.documentElement.outerHTML.slice(0,150000)})";
            // Return a value for synchronous expressions and report JS exceptions.
            web.evaluateJavascript("(()=>{try{return {ok:true,value:(0,eval)("+JSONObject.quote(script)+")};}catch(e){return {ok:false,error:String(e)}}})()",raw->{try{result.complete(new JSONObject(raw));}catch(Exception e){result.completeExceptionally(e);}});
        }catch(Exception error){result.completeExceptionally(error);}});return result.get(10,TimeUnit.SECONDS);
    }
}
