package app.aixs.mobile;

import android.app.*;
import android.content.Intent;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class AppUpdateService extends Service {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private static final AtomicBoolean running = new AtomicBoolean();
  static boolean active() { return running.get(); }
  private PowerManager.WakeLock wake;
  @Override public void onCreate() {
    super.onCreate();
    NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    notifications.createNotificationChannel(new NotificationChannel("updates", "Ai-XS 应用更新", NotificationManager.IMPORTANCE_LOW));
    PendingIntent screen = PendingIntent.getActivity(this, 0, new Intent(this, AboutActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    startForeground(62, new Notification.Builder(this, "updates").setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Ai-XS 应用更新").setContentText("正在下载并校验更新包").setContentIntent(screen).setOngoing(true).build());
    wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiXS:AppUpdate");
  }
  @Override public int onStartCommand(Intent intent, int flags, int id) {
    if (intent == null) { stopSelf(); return START_NOT_STICKY; }
    if (!running.compareAndSet(false, true)) return START_NOT_STICKY;
    String release = intent.getStringExtra("release");
    worker.execute(() -> {
      try {
        wake.acquire(30 * 60 * 1000L);
        AppUpdater.state(this, "downloading", "正在连接 GitHub 下载更新…");
        AppLogs.info(this, "app-update", "开始下载并校验应用更新");
        AppUpdater.download(this, new JSONObject(release));
        AppLogs.info(this, "app-update", "更新包下载与签名校验完成，可安装");
      } catch (Exception error) {
        AppLogs.failure(this, "app-update", "", "应用更新失败", error);
        try { AppUpdater.state(this, "failed", "更新失败：" + NativeEngine.message(error)); } catch (Exception ignored) {}
      } finally {
        if (wake.isHeld()) wake.release();
        running.set(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
      }
    });
    return START_NOT_STICKY;
  }
  @Override public IBinder onBind(Intent intent) { return null; }
  @Override public void onDestroy() {
    worker.shutdown();
    if (wake != null && wake.isHeld()) wake.release();
    super.onDestroy();
  }
}
