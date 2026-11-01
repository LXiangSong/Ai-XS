package app.aixs.mobile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Aggregates the per-member file reports of one collaboration turn. A worker's
 * inventory covers the shared project, so a path reported by two members is
 * deliberately treated as shared instead of guessing which process wrote it.
 */
final class CollaborationFiles {
  static final class Change {
    String path = "";
    String kind = "modified";
    String root = "";
    String diff = "";
    int added = -1;
    int removed = -1;
    final LinkedHashSet<String> memberKeys = new LinkedHashSet<>();
    final LinkedHashSet<String> memberNames = new LinkedHashSet<>();
  }

  static final class Member {
    String key = "";
    String name = "";
    String engine = "";
    String model = "";
    int created;
    int modified;
    int deleted;
    int added;
    int removed;
    final ArrayList<Change> files = new ArrayList<>();
  }

  static final class Report {
    String root = "";
    int total;
    int created;
    int modified;
    int deleted;
    int added;
    int removed;
    final ArrayList<Member> members = new ArrayList<>();
    final ArrayList<Change> shared = new ArrayList<>();
  }

  private CollaborationFiles() {}

  static Report of(JSONArray items) {
    Report result = new Report();
    LinkedHashMap<String, Change> changes = new LinkedHashMap<>();
    LinkedHashMap<String, Member> members = new LinkedHashMap<>();
    for (int i = 0; items != null && i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item == null || !"fileReport".equals(item.optString("type"))) continue;
      String participantId = item.optString("participantId", "");
      String participant = item.optString("participant", "");
      String engine = item.optString("engine", "");
      String model = item.optString("model", "");
      String key = !participantId.isEmpty() ? participantId
          : !participant.isEmpty() ? participant : engine + "|" + model;
      if (key.isEmpty()) key = "member-" + members.size();
      Member member = members.get(key);
      if (member == null) {
        member = new Member();
        member.key = key;
        member.name = !participant.isEmpty() ? participant : "协作成员 " + (members.size() + 1);
        member.engine = engine;
        member.model = model;
        members.put(key, member);
      } else {
        if (member.engine.isEmpty()) member.engine = engine;
        if (member.model.isEmpty()) member.model = model;
      }
      String root = item.optString("root", "");
      if (result.root.isEmpty() && !root.isEmpty()) result.root = root;
      JSONArray list = item.optJSONArray("changes");
      for (int j = 0; list != null && j < list.length(); j++) {
        JSONObject value = list.optJSONObject(j);
        if (value == null) continue;
        String path = value.optString("path", "");
        if (path.isEmpty()) continue;
        // Collaboration workers run in isolated workspaces. The same relative path in
        // two workspaces is two different files, not a shared edit.
        String changeKey = root + "\u0000" + path;
        Change change = changes.get(changeKey);
        if (change == null) {
          change = new Change();
          change.path = path;
          change.kind = value.optString("kind", "modified");
          change.root = root;
          change.diff = value.optString("diff", "");
          change.added = value.optInt("added", -1);
          change.removed = value.optInt("removed", -1);
          changes.put(changeKey, change);
        } else {
          if (change.kind.equals("modified") && !value.optString("kind", "").isEmpty())
            change.kind = value.optString("kind", change.kind);
          if (change.root.isEmpty()) change.root = root;
          if (change.diff.isEmpty()) change.diff = value.optString("diff", "");
          if (change.added < 0 && value.has("added")) change.added = value.optInt("added", -1);
          if (change.removed < 0 && value.has("removed")) change.removed = value.optInt("removed", -1);
        }
        change.memberKeys.add(key);
        change.memberNames.add(member.name);
      }
    }
    for (Iterator<Change> it = changes.values().iterator(); it.hasNext();) {
      Change change = it.next();
      result.total++;
      if ("created".equals(change.kind)) result.created++;
      else if ("deleted".equals(change.kind)) result.deleted++;
      else result.modified++;
      if (change.added > 0) result.added += change.added;
      if (change.removed > 0) result.removed += change.removed;
      if (change.memberKeys.size() == 1) {
        String key = change.memberKeys.iterator().next();
        Member member = members.get(key);
        if (member != null && !member.files.contains(change)) member.files.add(change);
      } else {
        result.shared.add(change);
      }
    }
    for (Member member : members.values()) {
      for (Change change : member.files) {
        if ("created".equals(change.kind)) member.created++;
        else if ("deleted".equals(change.kind)) member.deleted++;
        else member.modified++;
        if (change.added > 0) member.added += change.added;
        if (change.removed > 0) member.removed += change.removed;
      }
      result.members.add(member);
    }
    return result;
  }
}
