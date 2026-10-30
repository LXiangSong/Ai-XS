package app.aixs.mobile;

/** Preserve uncaught Android failures for the next launch, then use the system crash handler. */
public final class AiXsApplication extends android.app.Application {
  @Override public void onCreate() {
    super.onCreate();
    Thread.UncaughtExceptionHandler system = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
      try { AppLogs.failure(this, "app", "", "应用异常退出 · " + thread.getName(), error); }
      finally { if (system != null) system.uncaughtException(thread, error); }
    });
  }
}
