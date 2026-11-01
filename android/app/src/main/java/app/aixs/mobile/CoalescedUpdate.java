package app.aixs.mobile;

import java.util.function.Consumer;

/** Main-thread updates keep their first deadline even during a continuous stream. */
final class CoalescedUpdate implements Runnable {
  private final Runnable action;
  private final Consumer<Runnable> post, remove;
  private boolean pending;

  CoalescedUpdate(Runnable action, Consumer<Runnable> post, Consumer<Runnable> remove) {
    this.action = action;
    this.post = post;
    this.remove = remove;
  }

  void request() {
    if (pending) return;
    pending = true;
    post.accept(this);
  }

  void cancel() {
    remove.accept(this);
    pending = false;
  }

  @Override public void run() {
    pending = false;
    action.run();
  }
}
