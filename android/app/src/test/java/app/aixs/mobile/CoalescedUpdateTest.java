package app.aixs.mobile;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoalescedUpdateTest {
  @Test public void continuousTokensCannotPostponeTheFirstPaint() {
    TreeMap<Long, Runnable> tasks = new TreeMap<>();
    long[] now = {0};
    List<Long> painted = new ArrayList<>();
    StringBuilder incoming = new StringBuilder();
    List<Integer> lengths = new ArrayList<>();
    CoalescedUpdate update = new CoalescedUpdate(() -> {
      painted.add(now[0]); lengths.add(incoming.length());
    }, task -> tasks.put(now[0] + 80, task), task -> tasks.values().removeIf(t -> t == task));
    // Tokens never pause for the entire second; the old debounce would paint nothing.
    for (int ms = 0; ms <= 1000; ms += 10) {
      now[0] = ms;
      while (!tasks.isEmpty() && tasks.firstKey() <= ms) tasks.pollFirstEntry().getValue().run();
      incoming.append('x'); update.request();
      assertEquals(1, tasks.size());
    }
    assertEquals(Long.valueOf(80), painted.get(0));
    assertTrue(painted.size() >= 12);
    assertTrue(lengths.get(lengths.size()-1) > lengths.get(0));
    now[0] = tasks.firstKey(); tasks.pollFirstEntry().getValue().run();
    assertEquals(Integer.valueOf(101), lengths.get(lengths.size()-1));
  }

  @Test public void cancellingAndResumingLeavesNoStaleCallback() {
    List<Runnable> tasks = new ArrayList<>(); int[] paints = {0};
    CoalescedUpdate update = new CoalescedUpdate(() -> paints[0]++, tasks::add, tasks::remove);
    update.request(); update.request(); assertEquals(1, tasks.size());
    update.cancel(); assertTrue(tasks.isEmpty());
    update.request(); tasks.remove(0).run(); assertEquals(1, paints[0]);
    update.request(); assertEquals(1, tasks.size());
  }
}
