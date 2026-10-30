package app.aixs.mobile;

import android.app.*;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;

/** Separate preview WebView; no native JS bridge and no file/content URL access. */
public final class DeveloperBrowserActivity extends androidx.appcompat.app.AppCompatActivity {
  private WebView web;
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout body = NativeUi.page(this, "开发浏览器", this::finish);
    LinearLayout input = NativeUi.column(this);
    input.setPadding(NativeUi.dp(this, 16), 0, NativeUi.dp(this, 16), NativeUi.dp(this, 8));
    EditText address = NativeUi.field(this, "HTTPS 或本机 HTTP 地址", "", false);
    NativeUi.addField(this, input, address);
    Button go = NativeUi.primaryButton(this, "打开页面", () -> {});
    input.addView(go);
    body.addView(input);
    web = new WebView(this);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.getSettings().setAllowFileAccess(false);
    web.getSettings().setAllowContentAccess(false);
    web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    web.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return !allowed(request.getUrl());
      }
      @Override
      public void onPageFinished(WebView view, String url) {
        Uri uri = Uri.parse(url);
        address.setText(uri.buildUpon().query(null).fragment(null).build().toString());
      }
      @Override
      public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        Diagnostics.record(DeveloperBrowserActivity.this,
            "preview resource failure " + error.getErrorCode() + " " + origin(request.getUrl()));
      }
      @Override
      public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
        Diagnostics.record(
            DeveloperBrowserActivity.this, "preview HTTP " + response.getStatusCode() + " " + origin(request.getUrl()));
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override
      public boolean onConsoleMessage(ConsoleMessage message) {
        Diagnostics.record(DeveloperBrowserActivity.this,
            "preview " + message.messageLevel() + " line " + message.lineNumber() + " " + message.message());
        return true;
      }
    });
    go.setOnClickListener(v -> {
      Uri uri = Uri.parse(address.getText().toString().trim());
      if (allowed(uri))
        web.loadUrl(uri.toString());
      else
        address.setError("仅支持 HTTPS 或本机 HTTP 页面");
    });
    body.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
    getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        if (web.canGoBack())
          web.goBack();
        else
          finish();
      }
    });
  }
  private String origin(Uri uri) {
    return uri.buildUpon().query(null).fragment(null).build().toString();
  }
  private boolean allowed(Uri uri) {
    if (uri.getUserInfo() != null)
      return false;
    return "https".equals(uri.getScheme())
        || ("http".equals(uri.getScheme())
            && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())));
  }
  @Override
  protected void onResume() {
    super.onResume();
    BrowserTools.attach(this, web);
  }
  @Override
  protected void onDestroy() {
    BrowserTools.detach(this);
    web.destroy();
    super.onDestroy();
  }
}
