/*
 * Decompiled with CFR 0.152.
 */
package app.aixs.mobile;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.KeyEvent;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.util.LruCache;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.LifecycleOwner;
import app.aixs.mobile.AskQueue;
import app.aixs.mobile.ContextRing;
import app.aixs.mobile.ConversationContent;
import app.aixs.mobile.ConversationRenderer;
import app.aixs.mobile.NativeApprovals;
import app.aixs.mobile.NativeEngine;
import app.aixs.mobile.NativeToolsActivity;
import app.aixs.mobile.NativeUi;
import app.aixs.mobile.NativeWorkspaceActivity;
import app.aixs.mobile.R;
import app.aixs.mobile.RuntimeActivity;
import app.aixs.mobile.RuntimeManager;
import app.aixs.mobile.RuntimeService;
import app.aixs.mobile.Ui;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class NativeHomeActivity
extends AppCompatActivity
implements NativeEngine.Observer {
    private static final String[] ENGINE_IDS = new String[]{"codex", "claude", "deepseek"};
    private String engine = "codex";
    private ConversationCollaboration collaboration;
    private TextView modeLabel;
    private final HashMap<String,JSONObject> collaborationApprovals = new HashMap<>();
    private String model = "";
    private String effort = "high";
    private String cwd = "";
    private String threadId = "";
    private String turnId = "";
    private String permissionMode = "ask";
    private TextView permissionLabel;
    private JSONObject thread;
    private LinearLayout messages;
    private LinearLayout topbar;
    private LinearLayout composerHost;
    private LinearLayout drawerPanel;
    private LinearLayout statusStrip;
    private LinearLayout scrollBottom;
    private LinearLayout usageRow;
    private LinearLayout contextChip;
    private LinearLayout tokenChip;
    private LinearLayout sessionChip;
    private LinearLayout taskCapsule;
    private View taskCapsuleDot;
    private LinearLayout statsPopup;
    private boolean taskProgressPopup;
    private LinearLayout askHost;
    private ScrollView askOverlay;
    private TextView topTitle;
    private TextView topSubtitle;
    private TextView engineLabel;
    private LinearLayout insightButton;
    private TextView insightBadge;
    private SessionInsightPanel insightPanel;
    private SessionInsight currentInsight;
    private String insightChipState;
    private TextView statusText;
    private TextView attachBadge;
    private TextView modelLabel;
    private TextView effortLabel;
    private LinearLayout effortPill;
    private TextView usageText;
    private ContextRing usageRing;
    private TextView turnText;
    private TextView sessionStats;
    private LinearLayout enginePill;
    private LinearLayout projectPill;
    private LinearLayout sendButton;
    private ConversationQueue messageQueue;
    private LinearLayout attachButton;
    private LinearLayout drawerActive;
    private EditText composer;
    private ScrollView chat;
    private DrawerLayout drawer;
    private final ArrayList<String> modelOptions = new ArrayList();
    private final HashMap<String, JSONObject> modelMeta = new HashMap<>();
    private final ArrayList<String> modelLabels = new ArrayList();
    private final HashSet<String> expandedActivities = new HashSet();
    private final java.util.Set<String> autoExpandedActivities = new java.util.HashSet<>();
    private boolean followLatest = true;
    private boolean loading;
    private int engineGeneration;
    private final AgentViewCache viewCache = new AgentViewCache();
    private String pendingThread = "";
    private String failedThread = "";
    private int historyGeneration;
    private final HashMap<String,JSONObject> collaborationHistory = new HashMap<>();
    private boolean loadingCollaborationHistory;
    private int historyAnchorScroll = -1;
    private int historyAnchorHeight;
    private boolean submitting;
    private boolean resumed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<JSONObject> attachments = new ArrayList();
    private LinearLayout attachStrip;
    private HorizontalScrollView attachScroll;
    private LinearLayout quoteBar;
    private JSONObject quote;
    private final LruCache<String, Bitmap> thumbCache = new LruCache<>(24);
    private RuntimeManager thumbRuntime;
    private final HashSet<String> approvals = new HashSet();
    private String drawerTab = "threads";
    private String filterCwd = "";
    private String drawerQuery = "";
    private LinearLayout drawerList;
    private EditText drawerSearch;
    private final ArrayList<JSONObject> drawerThreads = new ArrayList();
    private final ArrayList<JSONObject> drawerProjects = new ArrayList();
    private long turnStartedAt;
    private JSONObject usage;
    private final Runnable ticker = new Runnable(){

        @Override
        public void run() {
            NativeHomeActivity.this.updateStatusLine();
            if (NativeHomeActivity.this.taskProgressPopup && NativeHomeActivity.this.statsPopup != null) {
                if (NativeHomeActivity.this.turnId.isEmpty()) NativeHomeActivity.this.dismissStatsPopup();
                else NativeHomeActivity.this.taskCapsulePopup();
            }
            if (!NativeHomeActivity.this.turnId.isEmpty()) {
                NativeHomeActivity.this.handler.postDelayed(this, 1000L);
            }
        }
    };
    private final CoalescedUpdate repaint = new CoalescedUpdate(() -> this.renderMessages(false),
        task -> this.handler.postDelayed(task, 80L), task -> this.handler.removeCallbacks(task));
    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false){

        public void handleOnBackPressed() {
            if (NativeHomeActivity.this.statsPopup != null) {
                NativeHomeActivity.this.dismissStatsPopup();
                return;
            }
            NativeHomeActivity.this.drawer.closeDrawer(androidx.core.view.GravityCompat.START);
        }
    };
    private BottomSheetDialog bottomSheet;
    private BottomSheetDialog currentSheet;
    private LinearLayout content;
    private boolean emptyRendered;
    private final ArrayList<LinearLayout> turnHosts = new ArrayList();
    private final ArrayList<String> turnSigs = new ArrayList();
    private final ArrayList<Boolean> turnRunning = new ArrayList();
    private ConversationRenderer renderer;
    private String rendererEngine = "";
    private String renderingEngine = "";
    private final HashMap<String, ConversationRenderer> agentRenderers = new HashMap<>();
    private JSONObject turnStats;
    private final AskQueue.Listener askListener = this::renderAsk;

    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        /* 键盘只由 WindowInsets 抬升输入区，避免 adjustResize 再缩放一次造成跳动。 */
        this.getWindow().setSoftInputMode(48);
        SharedPreferences prefs = this.getSharedPreferences("native-chat", 0);
        this.engine = prefs.getString("engine", "codex");
        this.cwd = prefs.getString("cwd", this.defaultWorkspace());
        this.threadId = prefs.getString("conversation-" + this.cwd.hashCode(), prefs.getString("thread-" + this.engine + "-" + this.cwd.hashCode(), ""));
        String requestedConversation = getIntent().getStringExtra("conversationId");
        if (requestedConversation != null && !requestedConversation.isEmpty()) this.threadId = requestedConversation;
        this.model = prefs.getString("model-" + this.engine, "");
        this.effort = prefs.getString("effort", "high");
        this.collaboration = new ConversationCollaboration(this, new ConversationCollaboration.Host(){
            public JSONObject thread(){return NativeHomeActivity.this.thread;}
            public String engine(){return NativeHomeActivity.this.engine;}
            public String model(){return NativeHomeActivity.this.model;}
            public String effort(){return NativeHomeActivity.this.effort;}
            public String permission(){return NativeHomeActivity.this.permissionMode;}
            public void apply(JSONObject value){NativeHomeActivity.this.applyThreadView(value);}
            public void controls(){NativeHomeActivity.this.updateCollaborationChrome();NativeHomeActivity.this.updateControls();}
            public void approval(JSONObject request){NativeHomeActivity.this.showApproval(request);}
            public void approvals(java.util.Set<String> valid){
                for(String id:new ArrayList<>(collaborationApprovals.keySet()))if(!valid.contains(id)){
                    collaborationApprovals.remove(id);approvals.remove(id);removeAsk(id);
                    for(int i=askHost.getChildCount()-1;i>=0;i--){Object tag=askHost.getChildAt(i).getTag();if(tag instanceof String&&((String)tag).startsWith(id+"-question-"))askHost.removeViewAt(i);}
                }
            }
        });
        this.messageQueue=new ConversationQueue(this,new ConversationQueue.Host(){
            public JSONObject thread(){return NativeHomeActivity.this.thread;}
            public void queue(JSONObject value){NativeHomeActivity.this.applyQueueView(value);}
            public boolean composerEmpty(){return !NativeHomeActivity.this.submitting && !NativeHomeActivity.this.composerContent();}
            public void restore(JSONArray input){NativeHomeActivity.this.restoreQueuedInput(input);}
        });
        this.build();
        this.drawer.addDrawerListener((DrawerLayout.DrawerListener)new DrawerLayout.SimpleDrawerListener(){

            public void onDrawerOpened(View view) {
                NativeHomeActivity.this.backCallback.setEnabled(true);
                NativeHomeActivity.this.refreshDrawerData();
            }

            public void onDrawerClosed(View view) {
                NativeHomeActivity.this.backCallback.setEnabled(false);
            }
        });
        this.composer.setText(prefs.getString("draft-" + this.engine, ""));
        AskQueue.listen(this.askListener);
        if (NativeEngine.ready()) {
            this.refresh();
        } else if (new RuntimeManager((Context)this, s -> {}).complete()) {
            this.startService();
        } else {
            this.showDisconnected();
            this.initializationHint();
        }
    }

    private void build() {
        NativeUi.palette((Activity)this);
        this.setContentView(R.layout.activity_home);
        this.drawer = (DrawerLayout)this.findViewById(R.id.home_drawer);
        this.topbar = (LinearLayout)this.findViewById(R.id.home_topbar);
        this.chat = (ScrollView)this.findViewById(R.id.chat_scroll);
        this.chat.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                this.hideKeyboard();
                this.dismissStatsPopup();
            }
            return false;
        });
        this.messages = (LinearLayout)this.findViewById(R.id.chat_messages);
        this.statusStrip = (LinearLayout)this.findViewById(R.id.status_strip);
        this.composerHost = (LinearLayout)this.findViewById(R.id.composer_host);
        /* 底部安全区落在输入框下沿，对话可以一直铺到屏幕底边。 */
        NativeUi.insets((Activity)this, this.findViewById(R.id.home_root), this.composerHost);
        this.drawerPanel = (LinearLayout)this.findViewById(R.id.home_drawer_panel);
        this.scrollBottom = (LinearLayout)this.findViewById(R.id.scroll_bottom);
        this.getOnBackPressedDispatcher().addCallback((LifecycleOwner)this, this.backCallback);
        ViewCompat.setOnApplyWindowInsetsListener((View)this.drawer, (v, w) -> {
            Insets bars = w.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            this.drawerPanel.setPadding(0, bars.top, 0, bars.bottom);
            return w;
        });
        ViewCompat.requestApplyInsets((View)this.drawer);
        this.drawer.setScrimColor(Ui.light ? 0x73000000 : -1509949440);
        this.drawer.setDrawerElevation(0.0f);
        this.buildTopbar();
        this.buildComposer();
        this.buildDrawer();
        this.chat.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (this.messages.getChildCount() == 0) {
                return;
            }
            int distance = this.messages.getHeight() - (y + this.chat.getHeight());
            this.followLatest = distance < Ui.dp((Context)this, 72);
            this.hintScrollButton();
        });
        this.scrollBottom.setBackgroundResource(R.drawable.bg_scroll_button);
        ImageView arrow = new ImageView((Context)this);
        arrow.setImageResource(R.drawable.ic_arrow_down);
        arrow.setColorFilter(Ui.text);
        this.scrollBottom.setElevation(Ui.dp((Context)this, 6));
        this.scrollBottom.addView((View)arrow, Ui.w(Ui.dp((Context)this, 18), Ui.dp((Context)this, 18)));
        Ui.touchable(this.scrollBottom);
        this.scrollBottom.setOnClickListener(v -> {
            this.followLatest = true;
            this.hintScrollButton();
            this.chat.smoothScrollTo(0, Math.max(0, this.messages.getHeight() - this.chat.getHeight() + this.chat.getPaddingBottom()));
        });
        this.placeScrollButton();
        this.renderMessages(false);
    }

    private void buildTopbar() {
        this.topbar.removeAllViews();
        this.topbar.addView(Ui.iconButton((Context)this, R.drawable.ic_menu, "\u6253\u5f00\u83dc\u5355", () -> this.drawer.openDrawer(androidx.core.view.GravityCompat.START)));
        LinearLayout titles = Ui.col((Context)this);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titleSpace.setMargins(Ui.dp((Context)this, 6), 0, Ui.dp((Context)this, 6), 0);
        this.topbar.addView((View)titles, titleSpace);
        this.topTitle = Ui.text((Context)this, "\u65b0\u5bf9\u8bdd", 15.5f, Ui.text, Ui.medium((Context)this));
        this.topTitle.setSingleLine(true);
        this.topTitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(this.topTitle);
        this.topSubtitle = Ui.text((Context)this, "", 11.5f, Ui.faint, null);
        this.topSubtitle.setSingleLine(true);
        this.topSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subtitleSpace = new LinearLayout.LayoutParams(-1, -2);
        subtitleSpace.topMargin = Ui.dp((Context)this, 2);
        titles.addView((View)this.topSubtitle, subtitleSpace);
        this.updateSubtitle();
        this.enginePill = Ui.pill((Context)this, 0, NativeUi.engineName(this.engine), true, this::chooseAgent);
        LinearLayout.LayoutParams pillSpace = new LinearLayout.LayoutParams(-2, Ui.dp((Context)this, 30));
        pillSpace.setMarginEnd(Ui.dp((Context)this, 2));
        this.topbar.addView((View)this.enginePill, pillSpace);
        this.engineLabel = (TextView)this.enginePill.getChildAt(this.enginePill.getChildCount() - 1);
        TextView collaborationMode = Ui.text(this, "独立 ▾", 11.5f, Ui.muted, null);
        this.modeLabel=collaborationMode;
        collaborationMode.setPadding(Ui.dp(this, 7), Ui.dp(this, 8), Ui.dp(this, 7), Ui.dp(this, 8));
        Ui.touchable(collaborationMode);
        collaborationMode.setContentDescription("切换独立或协作模式");
        collaborationMode.setOnClickListener(v -> this.openCollaboration());
        this.topbar.addView(collaborationMode);
        this.topbar.addView(this.insightEntry());
    }

    /**
     * 右上角入口：从“新建会话”改成会话详情。新建会话仍保留在抽屉顶部的主按钮，
     * 这里用图标加角标显示本会话的文件与子代理数量，执行中时高亮。
     */
    private View insightEntry() {
        FrameLayout box = new FrameLayout((Context)this);
        LinearLayout button = Ui.iconButton((Context)this, R.drawable.ic_insight, "\u4f1a\u8bdd\u8be6\u60c5", this::openInsight);
        box.addView((View)button, new FrameLayout.LayoutParams(Ui.dp((Context)this, 48), Ui.dp((Context)this, 48)));
        this.insightButton = button;
        this.insightBadge = Ui.text((Context)this, "", 9.5f, Ui.onAccentSoft, Ui.medium((Context)this));
        this.insightBadge.setGravity(17);
        this.insightBadge.setPadding(Ui.dp((Context)this, 4), 0, Ui.dp((Context)this, 4), 0);
        this.insightBadge.setMinWidth(Ui.dp((Context)this, 16));
        this.insightBadge.setMinHeight(Ui.dp((Context)this, 16));
        this.insightBadge.setBackground(Ui.round(Ui.accentSoft, Ui.dp((Context)this, 999)));
        this.insightBadge.setVisibility(View.GONE);
        this.insightBadge.setClickable(false);
        this.insightBadge.setFocusable(false);
        FrameLayout.LayoutParams badgeSpace = new FrameLayout.LayoutParams(-2, Ui.dp((Context)this, 16), Gravity.TOP | Gravity.END);
        badgeSpace.topMargin = Ui.dp((Context)this, 4);
        box.addView((View)this.insightBadge, badgeSpace);
        box.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp((Context)this, 48), Ui.dp((Context)this, 48)));
        this.insightChipState = null;
        this.updateInsightEntry();
        return box;
    }

    private void updateInsightEntry() {
        if (this.insightButton == null || this.insightBadge == null) return;
        SessionInsight insight = this.currentInsight != null ? this.currentInsight : SessionInsight.of(this.thread);
        int count = insight.files.size() + insight.subagents.size();
        boolean running = !this.turnId.isEmpty() || insight.activeSubagents > 0;
        View icon = this.insightButton.getChildCount() > 0 ? this.insightButton.getChildAt(0) : null;
        if (icon instanceof ImageView) ((ImageView)icon).setColorFilter(running ? Ui.accent : Ui.muted);
        this.insightBadge.setVisibility(count > 0 || running ? View.VISIBLE : View.GONE);
        this.insightBadge.setText(count > 99 ? "99+" : count > 0 ? String.valueOf(count) : "\u2022");
        this.insightBadge.setTextColor(running ? Ui.onAccent : Ui.onAccentSoft);
        this.insightBadge.setBackground(Ui.round(running ? Ui.accent : Ui.accentSoft, Ui.dp((Context)this, 999)));
        String state = count + "/" + running + "/" + insight.files.size() + "/" + insight.subagents.size() + "/" + insight.sources.size();
        if (state.equals(this.insightChipState)) return;
        this.insightChipState = state;
        StringBuilder description = new StringBuilder("\u4f1a\u8bdd\u8be6\u60c5");
        if (insight.files.size() > 0) description.append("\uff0c").append(insight.files.size()).append(" \u4e2a\u6587\u4ef6\u53d8\u5316");
        if (insight.subagents.size() > 0) description.append("\uff0c").append(insight.subagents.size()).append(" \u4e2a\u5b50\u4ee3\u7406");
        if (insight.sources.size() > 0) description.append("\uff0c").append(insight.sources.size()).append(" \u9879\u6765\u6e90");
        if (running) description.append("\uff0c\u6b63\u5728\u8fd0\u884c");
        this.insightButton.setContentDescription(description.toString());
    }

    private void openInsight() {
        if (!NativeEngine.ready()) {
            this.startService();
            return;
        }
        if (this.thread == null || this.threadId.isEmpty()) {
            if (!this.pendingThread.isEmpty()) NativeUi.toast((Activity)this, "\u6b63\u5728\u8bfb\u53d6\u4f1a\u8bdd\uff0c\u8bf7\u7a0d\u5019");
            else NativeUi.toast((Activity)this, "\u53d1\u9001\u7b2c\u4e00\u8f6e\u4efb\u52a1\u540e\uff0c\u8fd9\u91cc\u4f1a\u6c47\u603b\u6587\u4ef6\u3001\u5b50\u4ee3\u7406\u548c\u6765\u6e90");
            return;
        }
        this.sheet(sheet -> {
            SessionInsightPanel panel = new SessionInsightPanel(this, new SessionInsightPanel.Host() {
                @Override public void dismiss() { if (currentSheet != null) currentSheet.dismiss(); }
                @Override public void openEditor(String path) { NativeHomeActivity.this.openEditor(path); }
                @Override public void revealTurn(int turnNumber) { NativeHomeActivity.this.revealTurn(turnNumber); }
            });
            this.insightPanel = panel;
            panel.update(this.thread);
            this.content.addView(panel.build(), new LinearLayout.LayoutParams(-1, 0, 1.0f));
            sheet.setOnDismissListener(d -> {
                panel.release();
                if (this.insightPanel == panel) this.insightPanel = null;
            });
        }, 0.9);
    }

    /** 从详情面板回到原对话：展开该轮的“本轮文件”卡并滚到轮次开头。 */
    private void revealTurn(int turnNumber) {
        JSONArray turns = this.thread == null ? null : this.thread.optJSONArray("turns");
        JSONObject turn = turns == null ? null : turns.optJSONObject(turnNumber - 1);
        if (turn == null) return;
        this.sheetDismiss();
        if (this.currentSheet != null) {
            this.currentSheet.dismiss();
            this.currentSheet = null;
        }
        String turnId = turn.optString("id");
        JSONArray items = turn.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && "fileReport".equals(item.optString("type"))) {
                this.expandedActivities.add(turnId + ":" + item.optString("id"));
                break;
            }
        }
        this.followLatest = false;
        this.renderMessages(false);
        final int index = turnNumber - 1;
        this.chat.post(() -> {
            if (index < this.turnHosts.size()) {
                View host = this.turnHosts.get(index);
                this.chat.smoothScrollTo(0, Math.max(0, host.getTop() + this.messages.getTop() - Ui.dp((Context)this, 8)));
                this.hintScrollButton();
            }
        });
    }

    /** Claude-style diff review: one file block at a time, bounded and readable on a phone. */
    void showDiffs(JSONObject report) {
        if (report == null) return;
        String root = report.optString("root", "");
        JSONArray changes = report.optJSONArray("changes");
        LinearLayout body = Ui.col((Context)this);
        body.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
        boolean any = false;
        for (int i = 0; changes != null && i < changes.length(); i++) {
            JSONObject change = changes.optJSONObject(i);
            if (change == null) continue;
            String diff = change.optString("diff", "");
            if (diff.isEmpty()) continue;
            any = true;
            String path = change.optString("path", "");
            if (!root.isEmpty() && path.startsWith(root + "/")) path = path.substring(root.length() + 1);
            LinearLayout head = Ui.row((Context)this);
            head.setGravity(16);
            LinearLayout.LayoutParams headSpace = new LinearLayout.LayoutParams(-1, -2);
            headSpace.topMargin = Ui.dp(this, i == 0 ? 2 : 14);
            headSpace.bottomMargin = Ui.dp(this, 3);
            body.addView(head, headSpace);
            String kind = change.optString("kind", "modified");
            String tag = kind.equals("created") ? "新" : kind.equals("deleted") ? "删" : "改";
            int tagColor = kind.equals("created") ? Ui.mint : kind.equals("deleted") ? Ui.danger : Ui.accent;
            TextView badge = Ui.text((Context)this, tag, 10.0f, tagColor, Ui.medium(this));
            badge.setGravity(17);
            badge.setBackground(Ui.round(Ui.alpha(tagColor, 0.12f), Ui.dp(this, 7)));
            LinearLayout.LayoutParams badgeSpace = new LinearLayout.LayoutParams(Ui.dp(this, 21), Ui.dp(this, 19));
            badgeSpace.rightMargin = Ui.dp(this, 7);
            head.addView(badge, badgeSpace);
            TextView name = Ui.text((Context)this, path, 12.5f, Ui.text, Ui.medium((Context)this));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            int added = change.optInt("added", -1), removed = change.optInt("removed", -1);
            String statText = ConversationRenderer.diffStat(added, removed);
            if (!statText.isEmpty()) {
                TextView stat = Ui.text((Context)this, statText, 10.5f, Ui.faint, Ui.mono(this));
                LinearLayout.LayoutParams statSpace = new LinearLayout.LayoutParams(-2, -2);
                statSpace.leftMargin = Ui.dp(this, 8);
                head.addView(stat, statSpace);
            }
            TextView code = new TextView((Context)this);
            code.setTextSize(10.5f);
            code.setTypeface(Ui.mono(this));
            code.setIncludeFontPadding(false);
            code.setLineSpacing(Ui.dp(this, 1), 1f);
            code.setTextIsSelectable(true);
            code.setTextColor(Ui.muted);
            code.setPadding(Ui.dp(this, 7), Ui.dp(this, 4), Ui.dp(this, 7), Ui.dp(this, 4));
            code.setBackground(Ui.round(this, Ui.cardHigh, 8.0f, 0.8f, Ui.lineSoft));
            code.setText(diffSpans(diff));
            body.addView(code, Ui.fill());
        }
        if (!any) {
            TextView empty = Ui.text((Context)this, "这次没有可显示的文本差异（文件可能较大或不是文本）。", 12f, Ui.faint, null);
            empty.setLineSpacing(Ui.dp(this, 3), 1f);
            body.addView(empty);
        }
        CappedScrollView scroll = new CappedScrollView(this);
        scroll.setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.62f));
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        new MaterialAlertDialogBuilder(this).setTitle("本轮文件改动").setView(scroll)
                .setPositiveButton("关闭", null).show();
    }

    private CharSequence diffSpans(String diff) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = diff.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int start = out.length();
            out.append(line);
            int end = out.length();
            int color = 0, background = 0;
            if (line.startsWith("+")) { color = Ui.mint; background = Ui.alpha(Ui.mint, 0.13f); }
            else if (line.startsWith("-")) { color = Ui.danger; background = Ui.alpha(Ui.danger, 0.13f); }
            else if (line.startsWith("@@")) { color = Ui.accent; }
            if (color != 0) out.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (background != 0) out.setSpan(new BackgroundColorSpan(background), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i < lines.length - 1) out.append('\n');
        }
        return out;
    }

    void openEditor(String path) {
        if (path == null || path.isEmpty()) return;
        if (this.currentSheet != null) {
            this.currentSheet.dismiss();
            this.currentSheet = null;
        }
        int slash = path.lastIndexOf('/');
        String folder = slash > 0 ? path.substring(0, slash) : this.cwd;
        Intent intent = new Intent((Context)this, NativeWorkspaceActivity.class)
            .putExtra("engine", this.engine == null || this.engine.isEmpty() ? "codex" : this.engine)
            .putExtra("cwd", folder)
            .putExtra("file", path);
        try {
            this.startActivity(intent);
        } catch (Exception error) {
            NativeUi.toast((Activity)this, "\u65e0\u6cd5\u6253\u5f00\u6587\u4ef6\uff1a" + error.getMessage());
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);
        String conversation = intent.getStringExtra("conversationId");
        if (conversation != null && !conversation.isEmpty()) {
            this.pendingThread = "";this.viewCache.forget(conversation);
            if (NativeEngine.ready()) this.loadThread(conversation);
            else {this.threadId=conversation;this.thread=null;this.resumed=false;this.persist();this.renderMessages(false);}
        }
    }

    private boolean collaborationAfterTurn;
    private void openCollaboration() {
        if (!NativeEngine.ready()) { this.startService(); return; }
        if (!this.turnId.isEmpty() || this.submitting) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("进入协作")
                .setItems(new String[]{"当前任务完成后进入", "停止当前任务后进入"}, (d, which) -> {
                    this.collaborationAfterTurn = true;
                    if (which == 1) this.interrupt();
                    else NativeUi.toast(this, "已安排完成后进入协作，再次点击可取消");
                }).setNegativeButton("取消等待", (d,w) -> this.collaborationAfterTurn=false).show();
            return;
        }
        Runnable open = () -> this.collaboration.modes();
        if (this.threadId.isEmpty()) this.call("thread/start", NativeUi.json("cwd", this.cwd, "model", this.model), r -> {
            this.applyThreadView(r.optJSONObject("thread"));this.threadId=this.thread.optString("id");this.persist();open.run();
        }); else open.run();
    }

    private void updateCollaborationChrome(){
        if(this.collaboration==null)return;
        boolean enabled=this.collaboration.enabled();
        if(this.modeLabel!=null)this.modeLabel.setText(enabled?"协作 ▾":"独立 ▾");
        if(this.engineLabel!=null)this.engineLabel.setText(enabled?"成员 "+this.collaboration.members():NativeUi.engineName(this.engine));
        if(this.modelLabel!=null)this.modelLabel.setText(enabled?"成员模型":shortModel(this.model));
        if(this.effortLabel!=null)this.effortLabel.setText(enabled?"成员配置":this.effortName());
        this.updatePermissionPill();this.updateSubtitle();
    }

    private int composerWidth() {
        /* 与消息正文的 18dp 左右边距对齐，不再额外收窄。 */
        int available = Math.max(0, this.getResources().getDisplayMetrics().widthPixels - Ui.dp((Context)this, 36));
        return Math.min(Ui.dp((Context)this, 640), available);
    }

    /** 提问浮层可用高度：对话区高度减去输入区（含键盘占位）与上下留白。 */
    private int askOverlayMaxHeight() {
        View frame = this.findViewById(R.id.chat_frame);
        int frameHeight = frame == null ? 0 : frame.getHeight();
        if (frameHeight <= 0) frameHeight = this.getResources().getDisplayMetrics().heightPixels;
        int composer = this.composerHost == null ? 0 : this.composerHost.getHeight();
        int room = frameHeight - composer - Ui.dp(this, 24);
        return Math.max(Ui.dp(this, 140), room);
    }

    /** 内含独立滚动区的容器：只让选项滚，卡片头部与按钮行保持可见。 */
    static final class CappedScrollView extends ScrollView {
        private int maxHeight = Integer.MAX_VALUE;

        CappedScrollView(Context context) {
            super(context);
            setVerticalScrollBarEnabled(false);
        }

        void setMaxHeight(int value) {
            if (this.maxHeight == value) return;
            this.maxHeight = value;
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int capped = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                    ? this.maxHeight
                    : Math.min(MeasureSpec.getSize(heightSpec), this.maxHeight);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST));
        }
    }

    private void buildComposer() {
        this.composerHost.removeAllViews();
        if (this.statusStrip != null) {
            this.statusStrip.setVisibility(View.GONE);
        }
        this.askHost = Ui.col((Context)this);
        this.askHost.setClipChildren(false);
        this.askHost.setClipToPadding(false);
        Ui.pad(this.askHost, 8, 8, 8, 10);
        ScrollView questions = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) {
                if (askHost.getChildCount() == 0) {
                    setMeasuredDimension(View.MeasureSpec.getSize(width), 0);
                    return;
                }
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(askOverlayMaxHeight(), View.MeasureSpec.AT_MOST));
            }
        };
        questions.setClipToPadding(false);
        questions.addView(this.askHost);
        if (this.askOverlay != null && this.askOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup)this.askOverlay.getParent()).removeView(this.askOverlay);
        }
        this.askOverlay = questions;
        FrameLayout frame = (FrameLayout)this.findViewById(R.id.chat_frame);
        if (frame != null) {
            /* 浮层盖在输入区上方：不参与输入区排版，也不再改动对话区 padding。 */
            FrameLayout.LayoutParams askSpace = new FrameLayout.LayoutParams(this.composerWidth() + Ui.dp(this, 16), -2);
            askSpace.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            askSpace.bottomMargin = this.composerHost.getHeight();
            frame.addView((View)questions, askSpace);
        } else {
            LinearLayout.LayoutParams questionSpace = new LinearLayout.LayoutParams(this.composerWidth() + Ui.dp(this, 16), -2);
            questionSpace.gravity = Gravity.CENTER_HORIZONTAL;
            this.composerHost.addView(questions, questionSpace);
        }
        this.composerHost.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> this.chat.post(() -> {
            if (this.askOverlay != null && this.askOverlay.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams askSpace = (FrameLayout.LayoutParams)this.askOverlay.getLayoutParams();
                if (askSpace.bottomMargin != v.getHeight()) {
                    askSpace.bottomMargin = v.getHeight();
                    this.askOverlay.setLayoutParams(askSpace);
                }
            }
            this.placeScrollButton();
            int used = v.getHeight() + (this.statusStrip == null ? 0 : this.statusStrip.getHeight());
            if (this.chat.getPaddingBottom() != used) {
                this.chat.setPadding(this.chat.getPaddingLeft(), this.chat.getPaddingTop(), this.chat.getPaddingRight(), used);
                if (this.followLatest) {
                    this.chat.scrollTo(0, Math.max(0, this.messages.getHeight() - this.chat.getHeight() + used));
                }
            }
        }));
        int composerWidth = this.composerWidth();

        /* 运行任务胶囊：贴着输入区上方，空闲时完全隐藏。 */
        this.taskCapsule = Ui.row((Context)this);
        this.taskCapsule.setGravity(16);
        this.taskCapsule.setBackground(Ui.round((Context)this, Ui.cardHigh, 999.0f, 1.0f, Ui.lineSoft));
        Ui.pad(this.taskCapsule, 12, 0, 9, 0);
        this.taskCapsule.setMinimumHeight(Ui.dp((Context)this, 30));
        this.taskCapsuleDot = new View((Context)this);
        this.taskCapsule.addView(this.taskCapsuleDot, Ui.w(Ui.dp((Context)this, 6), Ui.dp((Context)this, 6)));
        this.statusText = Ui.text((Context)this, "", 11.0f, Ui.muted, Ui.medium((Context)this));
        this.statusText.setSingleLine(true);
        this.statusText.setMaxWidth(Ui.dp(this, 260));
        this.statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        this.statusText.setEllipsize(TextUtils.TruncateAt.END);
        this.statusText.setMaxWidth(Math.max(Ui.dp((Context)this, 120), composerWidth - Ui.dp((Context)this, 76)));
        LinearLayout.LayoutParams statusSpace = new LinearLayout.LayoutParams(-2, -2);
        statusSpace.leftMargin = Ui.dp((Context)this, 7);
        this.taskCapsule.addView((View)this.statusText, statusSpace);
        ImageView statusChevron = new ImageView((Context)this);
        statusChevron.setImageResource(R.drawable.ic_expand);
        statusChevron.setColorFilter(Ui.faint);
        LinearLayout.LayoutParams chevronSpace = Ui.w(Ui.dp((Context)this, 12), Ui.dp((Context)this, 12));
        chevronSpace.leftMargin = Ui.dp((Context)this, 4);
        this.taskCapsule.addView((View)statusChevron, chevronSpace);
        Ui.touchable(this.taskCapsule);
        Ui.rippleRounded(this.taskCapsule, 999, Ui.text);
        this.taskCapsule.setOnClickListener(v -> this.taskCapsulePopup());
        this.taskCapsule.setVisibility(View.GONE);
        LinearLayout.LayoutParams capsuleSpace = new LinearLayout.LayoutParams(-2, Ui.dp((Context)this, 30));
        capsuleSpace.gravity = 17;
        capsuleSpace.bottomMargin = Ui.dp((Context)this, 8);
        this.composerHost.addView((View)this.taskCapsule, capsuleSpace);

        LinearLayout above = Ui.col((Context)this);
        above.setGravity(16);
        Ui.pad(above, 0, 0, 0, 4);
        LinearLayout.LayoutParams aboveSpace = new LinearLayout.LayoutParams(composerWidth, -2);
        aboveSpace.gravity = 17;
        this.composerHost.addView((View)above, aboveSpace);
        LinearLayout queuePanel=Ui.col(this);
        above.addView(queuePanel,Ui.fill());
        this.messageQueue.attach(queuePanel);

        /* 底部安全区：顶边与输入框上沿对齐，背景与页面同色，不出现分界。 */
        LinearLayout dock = Ui.col((Context)this);
        dock.setBackgroundColor(Ui.bg);
        dock.setClipToPadding(false);
        LinearLayout.LayoutParams dockSpace = new LinearLayout.LayoutParams(composerWidth, -2);
        dockSpace.gravity = 17;
        this.composerHost.addView((View)dock, dockSpace);

        LinearLayout card = Ui.col((Context)this);
        card.setBackground(null);
        card.setGravity(17);
        dock.addView((View)card, Ui.fill());
        this.composer = new EditText((Context)this);
        this.composer.setBackground(null);
        this.composer.setHint("\u63cf\u8ff0\u4f60\u8981\u5b8c\u6210\u7684\u4efb\u52a1\u2026");
        this.composer.setHintTextColor(Ui.faint);
        this.composer.setTextColor(Ui.text);
        this.composer.setTextSize(15.0f);
        this.composer.setGravity(0x800033);
        this.composer.setMinLines(3);
        this.composer.setMaxLines(9);
        this.composer.setInputType(147457);
        this.composer.setPadding(Ui.dp((Context)this, 16), Ui.dp((Context)this, 14), Ui.dp((Context)this, 16), Ui.dp((Context)this, 8));
        this.composer.setBackgroundColor(0);
        this.composer.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(Editable text){NativeHomeActivity.this.updateExecutionButton();}
        });
        LinearLayout shell = Ui.col((Context)this);
        shell.setBackground(Ui.round((Context)this, Ui.card, 22.0f, 1.0f, Ui.lineSoft));
        LinearLayout.LayoutParams shellSpace = new LinearLayout.LayoutParams(composerWidth, -2);
        shellSpace.gravity = 17;
        card.addView((View)shell, shellSpace);
        this.quoteBar = Ui.col((Context)this);
        this.quoteBar.setBackground(Ui.round(Ui.cardHigh, Ui.dp((Context)this, 12)));
        this.quoteBar.setVisibility(View.GONE);
        Ui.pad(this.quoteBar, 10, 8, 6, 8);
        LinearLayout.LayoutParams quoteSpace = new LinearLayout.LayoutParams(-1, -2);
        quoteSpace.setMargins(Ui.dp((Context)this, 10), Ui.dp((Context)this, 10), Ui.dp((Context)this, 10), 0);
        shell.addView((View)this.quoteBar, quoteSpace);
        shell.addView((View)this.composer, Ui.fill());
        View inputDivider = new View((Context)this);
        inputDivider.setBackgroundColor(Ui.lineSoft);
        LinearLayout.LayoutParams inputDividerSpace = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp((Context)this, 0.6f)));
        inputDividerSpace.setMargins(Ui.dp((Context)this, 14), 0, Ui.dp((Context)this, 14), 0);
        shell.addView(inputDivider, inputDividerSpace);
        LinearLayout chips = Ui.row((Context)this);
        chips.setGravity(16);
        Ui.pad(chips, 0, 0, 0, 0);
        this.projectPill = Ui.pill((Context)this, R.drawable.ic_folder, NativeHomeActivity.folder(this.cwd), false, this::projects);
        TextView projectName = (TextView)this.projectPill.getChildAt(this.projectPill.getChildCount() - 1);
        projectName.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        projectName.setSingleLine(true);
        projectName.setMaxWidth(Ui.dp((Context)this, 150));
        projectName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams projectSpace = new LinearLayout.LayoutParams(-2, Ui.dp((Context)this, 30));
        projectSpace.setMarginEnd(Ui.dp((Context)this, 8));
        chips.addView((View)this.projectPill, projectSpace);
        View chipsGap = new View((Context)this);
        chips.addView(chipsGap, new LinearLayout.LayoutParams(0, 1, 1.0f));
        LinearLayout permissionPill = Ui.pill((Context)this, R.drawable.ic_check, this.permissionShort(), true, this::choosePermissionMode);
        permissionPill.setContentDescription("\u6743\u9650\u6a21\u5f0f");
        this.permissionLabel = (TextView)permissionPill.getChildAt(permissionPill.getChildCount() - 1);
        LinearLayout.LayoutParams permissionSpace = new LinearLayout.LayoutParams(-2, Ui.dp((Context)this, 30));
        chips.addView((View)permissionPill, permissionSpace);
        above.addView((View)chips, Ui.fill());
        this.attachScroll = new HorizontalScrollView((Context)this);
        this.attachScroll.setHorizontalScrollBarEnabled(false);
        this.attachScroll.setClipToPadding(false);
        this.attachScroll.setVisibility(View.GONE);
        this.attachStrip = Ui.row((Context)this);
        this.attachStrip.setGravity(16);
        this.attachScroll.addView((View)this.attachStrip, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams stripSpace = new LinearLayout.LayoutParams(-1, -2);
        stripSpace.topMargin = Ui.dp((Context)this, 6);
        above.addView((View)this.attachScroll, stripSpace);
        this.updateUsageRow();
        LinearLayout actions = Ui.row((Context)this);
        actions.setGravity(16);
        actions.setPadding(Ui.dp((Context)this, 2), 0, Ui.dp((Context)this, 2), 0);
        actions.setMinimumHeight(Ui.dp((Context)this, 44));
        shell.addView((View)actions, Ui.fill());
        this.attachButton = Ui.iconButton((Context)this, R.drawable.ic_attachment, "\u6dfb\u52a0\u56fe\u7247", this::pickImage);
        this.attachButton.setLayoutParams(Ui.w(Ui.dp((Context)this, 40), Ui.dp((Context)this, 40)));
        actions.addView(this.attachButton);
        this.attachBadge = null;
        this.modelLabel = Ui.text((Context)this, this.model.isEmpty() ? "\u9009\u62e9\u6a21\u578b" : NativeHomeActivity.shortModel(this.model), 12.0f, Ui.muted, Ui.medium((Context)this));
        LinearLayout modelPill = Ui.row((Context)this);
        modelPill.setGravity(17);
        modelPill.setBackground(Ui.round(Ui.cardHigh, Ui.dp((Context)this, 999)));
        Ui.pad(modelPill, 8, 0, 8, 0);
        modelPill.setMinimumHeight(Ui.dp((Context)this, 30));
        this.modelLabel.setSingleLine(true);
        this.modelLabel.setEllipsize(TextUtils.TruncateAt.END);
        this.modelLabel.setMaxWidth(Ui.dp((Context)this, 120));
        modelPill.addView((View)this.modelLabel, new LinearLayout.LayoutParams(-2, -2));
        ImageView caret = new ImageView((Context)this);
        caret.setImageResource(R.drawable.ic_expand);
        caret.setColorFilter(Ui.faint);
        LinearLayout.LayoutParams caretSpace = Ui.w(Ui.dp((Context)this, 14), Ui.dp((Context)this, 14));
        caretSpace.leftMargin = Ui.dp((Context)this, 2);
        modelPill.addView((View)caret, caretSpace);
        Ui.touchable(modelPill);
        Ui.ripple(modelPill, Ui.text);
        modelPill.setOnClickListener(v -> this.chooseModel());
        LinearLayout.LayoutParams modelSpace = new LinearLayout.LayoutParams(-2, Ui.dp((Context)this, 30));
        modelSpace.setMarginStart(Ui.dp((Context)this, 4));
        actions.addView((View)modelPill, modelSpace);
        this.effortLabel = Ui.text((Context)this, this.effortName(), 12.0f, Ui.muted, Ui.medium((Context)this));
        this.effortLabel.setSingleLine(true);
        this.effortPill = Ui.row((Context)this);
        this.effortPill.setGravity(17);
        this.effortPill.setBackground(Ui.round(Ui.cardHigh, Ui.dp((Context)this, 999)));
        Ui.pad(this.effortPill, 10, 0, 10, 0);
        this.effortPill.setMinimumHeight(Ui.dp((Context)this, 30));
        this.effortPill.addView(this.effortLabel);
        Ui.touchable(this.effortPill);
        Ui.ripple(this.effortPill, Ui.text);
        this.effortPill.setOnClickListener(v -> this.chooseEffort());
        LinearLayout.LayoutParams effortSpace = new LinearLayout.LayoutParams(-2, Ui.dp((Context)this, 30));
        effortSpace.setMarginStart(Ui.dp((Context)this, 6));
        actions.addView((View)this.effortPill, effortSpace);
        View spacer = new View((Context)this);
        actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1.0f));

        /* 三个统计入口共用一条分段栏：视觉上是一整块，点击仍然各自独立。 */
        this.usageRow = Ui.row((Context)this);
        this.usageRow.setGravity(16);
        this.usageRow.setBackground(Ui.round(Ui.card, Ui.dp((Context)this, 14)));
        this.usageRow.setClipChildren(false);
        this.usageRow.setClipToPadding(false);
        Ui.pad(this.usageRow, 2, 2, 2, 2);

        this.contextChip = Ui.col((Context)this);
        this.contextChip.setGravity(17);
        Ui.pad(this.contextChip, 6, 5, 6, 5);
        LinearLayout contextTop = Ui.row((Context)this);
        contextTop.setGravity(16);
        this.usageRing = new ContextRing((Context)this);
        contextTop.addView((View)this.usageRing, Ui.w(Ui.dp((Context)this, 11), Ui.dp((Context)this, 11)));
        TextView contextLabel = Ui.text((Context)this, "上下文", 9.0f, Ui.faint, Ui.medium((Context)this));
        LinearLayout.LayoutParams contextLabelSpace = new LinearLayout.LayoutParams(-2, -2);
        contextLabelSpace.leftMargin = Ui.dp((Context)this, 4);
        contextTop.addView((View)contextLabel, contextLabelSpace);
        this.contextChip.addView((View)contextTop);
        this.usageText = Ui.text((Context)this, "--", 10.5f, Ui.muted, Ui.medium((Context)this));
        this.usageText.setSingleLine(true);
        this.usageText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams usageTextSpace = new LinearLayout.LayoutParams(-2, -2);
        usageTextSpace.topMargin = Ui.dp((Context)this, 1);
        this.contextChip.addView((View)this.usageText, usageTextSpace);
        this.bindStatsChip(this.contextChip, "上下文与压缩", this::contextPopup);
        this.usageRow.addView((View)this.contextChip, new LinearLayout.LayoutParams(0, -1, 1.0f));
        this.usageRow.addView(this.statDivider());

        this.tokenChip = Ui.col((Context)this);
        this.tokenChip.setGravity(17);
        Ui.pad(this.tokenChip, 6, 5, 6, 5);
        TextView tokenLabel = Ui.text((Context)this, "Token", 9.0f, Ui.faint, Ui.medium((Context)this));
        tokenLabel.setGravity(Gravity.CENTER);
        this.tokenChip.addView((View)tokenLabel, new LinearLayout.LayoutParams(-2, -2));
        this.turnText = Ui.text((Context)this, "--", 10.5f, Ui.muted, Ui.medium((Context)this));
        this.turnText.setSingleLine(true);
        this.turnText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams turnTextSpace = new LinearLayout.LayoutParams(-2, -2);
        turnTextSpace.topMargin = Ui.dp((Context)this, 1);
        this.tokenChip.addView((View)this.turnText, turnTextSpace);
        this.bindStatsChip(this.tokenChip, "本轮 Token 用量", this::tokenPopup);
        this.usageRow.addView((View)this.tokenChip, new LinearLayout.LayoutParams(0, -1, 1.0f));
        this.usageRow.addView(this.statDivider());

        this.sessionChip = Ui.col((Context)this);
        this.sessionChip.setGravity(17);
        Ui.pad(this.sessionChip, 6, 5, 6, 5);
        TextView sessionLabel = Ui.text((Context)this, "会话", 9.0f, Ui.faint, Ui.medium((Context)this));
        sessionLabel.setGravity(Gravity.CENTER);
        this.sessionChip.addView((View)sessionLabel, new LinearLayout.LayoutParams(-2, -2));
        this.sessionStats = Ui.text((Context)this, "--", 10.5f, Ui.muted, Ui.medium((Context)this));
        this.sessionStats.setSingleLine(true);
        this.sessionStats.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sessionStatsSpace = new LinearLayout.LayoutParams(-2, -2);
        sessionStatsSpace.topMargin = Ui.dp((Context)this, 1);
        this.sessionChip.addView((View)this.sessionStats, sessionStatsSpace);
        this.bindStatsChip(this.sessionChip, "会话统计", this::sessionPopup);
        this.usageRow.addView((View)this.sessionChip, new LinearLayout.LayoutParams(0, -1, 1.0f));

        this.usageRow.setContentDescription("上下文、Token 用量与会话统计");
        LinearLayout.LayoutParams usageSpace = new LinearLayout.LayoutParams(-1, Ui.dp((Context)this, 44));
        usageSpace.gravity = 17;
        usageSpace.setMargins(0, Ui.dp((Context)this, 8), 0, Ui.dp((Context)this, 8));
        dock.addView((View)this.usageRow, usageSpace);
        this.sendButton = this.circle(R.drawable.ic_send, Ui.accent, Ui.onAccent, "执行任务", this::executionClick);
        LinearLayout.LayoutParams sendSpace = new LinearLayout.LayoutParams(Ui.dp((Context)this, 38), Ui.dp((Context)this, 38));
        sendSpace.setMarginStart(Ui.dp((Context)this, 8));
        actions.addView((View)this.sendButton, sendSpace);
        this.updateControls();
    }

    private LinearLayout circle(int icon, int fill, int tint, String description, Runnable click) {
        LinearLayout box = new LinearLayout((Context)this);
        box.setGravity(17);
        box.setBackground(Ui.round(fill, Ui.dp((Context)this, 999)));
        box.setContentDescription(description);
        ImageView image = new ImageView((Context)this);
        image.setImageResource(icon);
        image.setColorFilter(tint);
        box.addView((View)image, Ui.w(Ui.dp((Context)this, 18), Ui.dp((Context)this, 18)));
        Ui.touchable(box);
        box.setOnClickListener(v -> click.run());
        return box;
    }

    private void buildDrawer() {
        this.drawerPanel.removeAllViews();
        this.drawerPanel.addView(this.drawerHeader());
        LinearLayout newWrap = Ui.col((Context)this);
        newWrap.setPadding(Ui.dp((Context)this, 12), 0, Ui.dp((Context)this, 12), 0);
        newWrap.addView(this.drawerPrimary());
        this.drawerPanel.addView(newWrap);
        this.drawerPanel.addView(this.drawerShelf());
        ScrollView scroll = new ScrollView((Context)this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        this.drawerList = Ui.col((Context)this);
        this.drawerList.setPadding(Ui.dp((Context)this, 10), Ui.dp((Context)this, 2), Ui.dp((Context)this, 10), Ui.dp((Context)this, 10));
        scroll.addView(this.drawerList);
        this.drawerPanel.addView((View)scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        this.drawerPanel.addView(this.drawerFooter());
        this.renderDrawer();
    }

    private View drawerShelf() {
        String[][] tabs;
        LinearLayout shelf = Ui.col((Context)this);
        shelf.setPadding(Ui.dp((Context)this, 12), Ui.dp((Context)this, 12), Ui.dp((Context)this, 12), Ui.dp((Context)this, 8));
        LinearLayout track = Ui.row((Context)this);
        track.setBackground(Ui.round(Ui.cardHigh, Ui.dp((Context)this, 999)));
        track.setPadding(Ui.dp((Context)this, 4), Ui.dp((Context)this, 4), Ui.dp((Context)this, 4), Ui.dp((Context)this, 4));
        for (String[] tab : tabs = new String[][]{{"threads", "\u4f1a\u8bdd"}, {"projects", "\u9879\u76ee"}}) {
            boolean active = tab[0].equals(this.drawerTab);
            LinearLayout segment = new LinearLayout((Context)this);
            segment.setGravity(17);
            segment.setBackground(Ui.round(active ? Ui.card : 0, Ui.dp((Context)this, 999)));
            segment.setMinimumHeight(Ui.dp((Context)this, 32));
            segment.addView(Ui.text((Context)this, tab[1], 12.5f, active ? Ui.text : Ui.muted, active ? Ui.medium((Context)this) : null));
            Ui.touchable(segment);
            if (!active) {
                Ui.rippleRounded(segment, 999, Ui.muted);
            }
            segment.setOnClickListener(v -> this.setDrawerTab(tab[0]));
            track.addView((View)segment, new LinearLayout.LayoutParams(0, -2, 1.0f));
        }
        shelf.addView(track);
        LinearLayout box = Ui.row((Context)this);
        box.setGravity(16);
        box.setBackground(Ui.round(Ui.cardHigh, Ui.dp((Context)this, 12)));
        box.setPadding(Ui.dp((Context)this, 10), 0, Ui.dp((Context)this, 8), 0);
        ImageView lens = new ImageView((Context)this);
        lens.setImageResource(R.drawable.ic_search);
        lens.setColorFilter(Ui.faint);
        box.addView((View)lens, Ui.w(Ui.dp((Context)this, 16), Ui.dp((Context)this, 16)));
        this.drawerSearch = new EditText((Context)this);
        this.drawerSearch.setBackground(null);
        this.drawerSearch.setSingleLine(true);
        this.drawerSearch.setTextSize(13.5f);
        this.drawerSearch.setTextColor(Ui.text);
        this.drawerSearch.setHintTextColor(Ui.faint);
        this.drawerSearch.setHint(this.drawerTab.equals("projects") ? "\u641c\u7d22\u9879\u76ee" : "\u641c\u7d22\u4f1a\u8bdd");
        this.drawerSearch.setText(this.drawerQuery);
        this.drawerSearch.setFocusableInTouchMode(false);
        this.drawerSearch.setOnClickListener(v -> this.drawerSearch.setFocusableInTouchMode(true));
        this.drawerSearch.setSelection(this.drawerSearch.getText().length());
        LinearLayout.LayoutParams searchSpace = new LinearLayout.LayoutParams(0, -2, 1.0f);
        searchSpace.leftMargin = Ui.dp((Context)this, 8);
        box.addView((View)this.drawerSearch, searchSpace);
        this.drawerSearch.addTextChangedListener(new TextWatcher(){

            @Override
            public void afterTextChanged(Editable value) {
                NativeHomeActivity.this.drawerQuery = value.toString();
                NativeHomeActivity.this.renderDrawer();
            }

            @Override
            public void beforeTextChanged(CharSequence v, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence v, int a, int b, int c) {
            }
        });
        LinearLayout.LayoutParams boxSpace = Ui.fill(Ui.dp((Context)this, 42));
        boxSpace.topMargin = Ui.dp((Context)this, 8);
        shelf.addView((View)box, boxSpace);
        return shelf;
    }

    private void setDrawerTab(String tab) {
        if (tab.equals(this.drawerTab)) {
            return;
        }
        this.drawerTab = tab;
        this.filterCwd = "";
        this.drawerQuery = "";
        this.buildDrawer();
        this.refreshDrawerData();
    }

    private void listSessions(Consumer<JSONObject> done) {
        this.listSessionsPage("", new JSONArray(), done);
    }

    private void listSessionsPage(String cursor, JSONArray all, Consumer<JSONObject> done) {
        this.call("native/threads/list", NativeUi.json("limit", 500, "archived", false, "cursor", cursor), r -> {
            JSONArray page = r.optJSONArray("data");
            if (page != null) {
                for (int i = 0; i < page.length(); i++) all.put(page.optJSONObject(i));
            }
            String next = ConversationContent.string(r, "nextCursor", "");
            if (!next.isEmpty()) {
                this.listSessionsPage(next, all, done);
                return;
            }
            try { r.put("data", all); } catch (JSONException ignored) {}
            JSONArray errors = r.optJSONArray("errors");
            if (errors != null && errors.length() > 0) {
                ArrayList<String> names = new ArrayList<>();
                for (int i = 0; i < errors.length(); i++) {
                    JSONObject error = errors.optJSONObject(i);
                    if (error != null) names.add(NativeUi.engineName(error.optString("engine")));
                }
                NativeUi.toast(this, "部分会话暂时无法读取：" + android.text.TextUtils.join("、", names));
            }
            done.accept(r);
        });
    }

    private void refreshDrawerData() {
        if (!NativeEngine.ready()) {
            this.renderDrawer();
            return;
        }
        this.listSessions(r -> {
            JSONArray data = r.optJSONArray("data");
            this.drawerThreads.clear();
            if (data != null) {
                for (int i = 0; i < data.length(); ++i) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) continue;
                    this.drawerThreads.add(item);
                }
            }
            this.renderDrawer();
        });
        this.quiet("projects/list", NativeUi.json(new Object[0]), r -> {
            JSONArray data = r.optJSONArray("data");
            this.drawerProjects.clear();
            if (data != null) {
                for (int i = 0; i < data.length(); ++i) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) continue;
                    this.drawerProjects.add(item);
                }
            }
            this.renderDrawer();
        });
    }

    private void renderDrawer() {
        if (this.drawerList == null) {
            return;
        }
        this.drawerList.removeAllViews();
        if (!this.filterCwd.isEmpty()) {
            this.drawerList.addView(this.drawerFilterChip());
        }
        if (this.drawerTab.equals("projects")) {
            this.renderDrawerProjects();
        } else {
            this.renderDrawerThreads();
        }
    }

    private View drawerFilterChip() {
        LinearLayout row = Ui.row((Context)this);
        row.setGravity(16);
        row.setBackground(Ui.round(Ui.accentSoft, Ui.dp((Context)this, 12)));
        row.setPadding(Ui.dp((Context)this, 11), Ui.dp((Context)this, 8), Ui.dp((Context)this, 9), Ui.dp((Context)this, 8));
        row.addView((View)Ui.text((Context)this, "\u4ec5\u770b " + NativeHomeActivity.folder(this.filterCwd), 12.0f, Ui.onAccentSoft, Ui.medium((Context)this)), new LinearLayout.LayoutParams(0, -2, 1.0f));
        ImageView close = new ImageView((Context)this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(Ui.onAccentSoft);
        row.addView((View)close, Ui.w(Ui.dp((Context)this, 14), Ui.dp((Context)this, 14)));
        Ui.touchable(row);
        Ui.rippleRounded(row, 12, Ui.onAccentSoft);
        row.setOnClickListener(v -> {
            this.filterCwd = "";
            this.renderDrawer();
        });
        LinearLayout.LayoutParams space = Ui.fill();
        space.bottomMargin = Ui.dp((Context)this, 8);
        row.setLayoutParams(space);
        return row;
    }

    private void createProject() {
        try {
            Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT_TREE");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            this.startActivityForResult(intent, 73);
        }
        catch (Exception e) {
            NativeUi.error((Activity)this, "\u6ca1\u6709\u53ef\u7528\u7684\u6587\u4ef6\u7ba1\u7406\u5668\uff1a" + e.getMessage());
        }
    }

    private String folderPath(Uri uri) {
        try {
            int colon;
            String docId = DocumentsContract.getTreeDocumentId(uri);
            int n = colon = docId == null ? -1 : docId.indexOf(58);
            if (colon <= 0) {
                return "";
            }
            String type = docId.substring(0, colon);
            String rest = docId.substring(colon + 1);
            if ("primary".equalsIgnoreCase(type) || "home".equalsIgnoreCase(type)) {
                return new File(Environment.getExternalStorageDirectory(), rest).getAbsolutePath();
            }
            return new File("/storage/" + type, rest).getAbsolutePath();
        }
        catch (Exception e) {
            return "";
        }
    }

    private void addProjectFolder(Uri uri) {
        String path = this.folderPath(uri);
        if (path.isEmpty()) {
            NativeUi.error((Activity)this, "\u8fd9\u4e2a\u4f4d\u7f6e\u65e0\u6cd5\u89e3\u6790\u4e3a\u672c\u5730\u8def\u5f84\uff0c\u8bf7\u6362\u4e00\u4e2a\u6587\u4ef6\u5939");
            return;
        }
        File dir = new File(path);
        if (!dir.exists()) {
            NativeUi.error((Activity)this, "\u6587\u4ef6\u5939\u4e0d\u5b58\u5728\uff1a" + path);
            return;
        }
        String folder = dir.getName();
        String name = folder.isEmpty() ? path : folder;
        NativeEngine.call("projects/add", NativeUi.json("path", path, "name", name), r -> {
            this.sheetDismiss();
            NativeUi.toast((Activity)this, "\u5df2\u6dfb\u52a0\u9879\u76ee\uff1a" + name);
            this.quiet("projects/list", NativeUi.json(new Object[0]), list -> {
                JSONArray data = list.optJSONArray("data");
                this.drawerProjects.clear();
                if (data != null) {
                    for (int i = 0; i < data.length(); ++i) {
                        JSONObject item = data.optJSONObject(i);
                        if (item == null) continue;
                        this.drawerProjects.add(item);
                    }
                }
                this.selectProject(path, true);
                this.renderDrawerProjects();
                this.renderDrawer();
            });
        }, m -> NativeUi.error((Activity)this, m));
    }

    private void sheetDismiss() {
        if (this.bottomSheet != null) {
            this.bottomSheet.dismiss();
            this.bottomSheet = null;
        }
    }

    private void pathProjectDialog() {
        LinearLayout form = Ui.col((Context)this);
        form.setPadding(Ui.dp((Context)this, 20), 0, Ui.dp((Context)this, 20), 0);
        EditText path = NativeUi.field((Activity)this, "\u7edd\u5bf9\u8def\u5f84\uff0c\u4f8b\u5982 /home/phone/projects/new-app", this.cwd, false);
        NativeUi.addField((Activity)this, form, path);
        EditText name = NativeUi.field((Activity)this, "\u9879\u76ee\u540d\u79f0\uff08\u7559\u7a7a\u7528\u6587\u4ef6\u5939\u540d\uff09", "", false);
        NativeUi.addField((Activity)this, form, name);
        AlertDialog dialog = new MaterialAlertDialogBuilder((Context)this).setTitle((CharSequence)"\u65b0\u5efa\u9879\u76ee").setView((View)form).setNegativeButton((CharSequence)"\u53d6\u6d88", null).setPositiveButton((CharSequence)"\u6dfb\u52a0", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {
            String value = path.getText().toString().trim();
            if (!value.startsWith("/")) {
                path.setError("\u9700\u8981\u7edd\u5bf9\u8def\u5f84");
                return;
            }
            NativeEngine.call("projects/add", NativeUi.json("path", value, "name", name.getText().toString().trim()), r -> {
                dialog.dismiss();
                NativeUi.toast((Activity)this, "\u5df2\u52a0\u5165\u9879\u76ee");
                this.refreshDrawerData();
                this.selectProject(value, true);
            }, m -> NativeUi.error((Activity)this, m));
        }));
        dialog.show();
    }

    private void renderDrawerThreads() {
        String needle = this.drawerQuery.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        java.util.LinkedHashMap<String, ArrayList<JSONObject>> groups = new java.util.LinkedHashMap<String, ArrayList<JSONObject>>();
        for (JSONObject item : this.sortedThreads()) {
            String title = ConversationContent.title(item);
            String preview = ConversationContent.string(item, "preview", "");
            String path = ConversationContent.string(item, "cwd", "");
            if (!this.filterCwd.isEmpty() && !this.filterCwd.equals(path) || !needle.isEmpty() && !(title + " " + preview + " " + path).toLowerCase(Locale.ROOT).contains(needle)) continue;
            ArrayList<JSONObject> bucket = groups.get(path);
            if (bucket == null) {
                bucket = new ArrayList<JSONObject>();
                groups.put(path, bucket);
            }
            bucket.add(item);
        }
        for (String path : groups.keySet()) {
            ArrayList<JSONObject> bucket = groups.get(path);
            TextView group = Ui.sectionLabel((Context)this, NativeHomeActivity.folder(path.isEmpty() ? "未设置目录" : path) + " \u00b7 " + bucket.size() + " \u4e2a\u4f1a\u8bdd");
            group.setPadding(Ui.dp((Context)this, 8), Ui.dp((Context)this, 10), 0, Ui.dp((Context)this, 4));
            this.drawerList.addView(group);
            for (JSONObject item : bucket) {
                String stamp = ConversationContent.stamp(item, "updatedAt", "createdAt");
                this.drawerList.addView(this.drawerThreadRow(item, ConversationContent.title(item), path, stamp));
                ++shown;
            }
        }
        if (shown == 0) {
            Ui.empty(this.drawerList, R.drawable.ic_history, needle.isEmpty() ? (this.filterCwd.isEmpty() ? "\u8fd8\u6ca1\u6709\u4f1a\u8bdd" : "\u8be5\u9879\u76ee\u8fd8\u6ca1\u6709\u4f1a\u8bdd") : "\u6ca1\u6709\u5339\u914d\u7684\u4f1a\u8bdd", needle.isEmpty() ? "\u53d1\u9001\u7b2c\u4e00\u6761\u4efb\u52a1\u540e\uff0c\u4f1a\u8bdd\u4f1a\u51fa\u73b0\u5728\u8fd9\u91cc\u3002" : "\u6362\u4e2a\u5173\u952e\u8bcd\u8bd5\u8bd5\u3002");
        }
    }

    private View drawerThreadRow(JSONObject item, String title, String path, String stamp) {
        boolean active = item.optString("id").equals(this.threadId);
        LinearLayout row = Ui.col((Context)this);
        row.setBackground(Ui.round(active ? Ui.accentSoft : 0, Ui.dp((Context)this, 12)));
        row.setPadding(Ui.dp((Context)this, 11), Ui.dp((Context)this, 9), Ui.dp((Context)this, 11), Ui.dp((Context)this, 9));
        TextView name = Ui.text((Context)this, title, 13.5f, active ? Ui.onAccentSoft : Ui.text, Ui.medium((Context)this));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name);
        TextView meta = Ui.text((Context)this, NativeHomeActivity.join(" \u00b7 ", NativeUi.engineName(item.optString("engine", threadEngine(item.optString("id")))), NativeHomeActivity.folder(path), NativeHomeActivity.dayLabel(stamp)), 11.0f, active ? Ui.onAccentSoft : Ui.faint, null);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaSpace = Ui.fill();
        metaSpace.topMargin = Ui.dp((Context)this, 3);
        row.addView((View)meta, metaSpace);
        Ui.touchable(row);
        if (!active) {
            Ui.rippleRounded(row, 12, Ui.text);
        }
        row.setOnClickListener(v -> this.openThread(item));
        row.setOnLongClickListener(v -> {
            this.sessionActions(item);
            return true;
        });
        LinearLayout.LayoutParams space = Ui.fill();
        space.bottomMargin = Ui.dp((Context)this, 2);
        row.setLayoutParams(space);
        return row;
    }

    private void renderDrawerProjects() {
        LinearLayout create = Ui.row((Context)this);
        create.setGravity(16);
        create.setPadding(Ui.dp((Context)this, 10), Ui.dp((Context)this, 9), Ui.dp((Context)this, 10), Ui.dp((Context)this, 9));
        create.setBackground(Ui.round((Context)this, Ui.accentSoft, 12.0f, 0.0f, Ui.accentSoft));
        ImageView plus = new ImageView((Context)this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setColorFilter(Ui.onAccentSoft);
        create.addView((View)plus, Ui.w(Ui.dp((Context)this, 16), Ui.dp((Context)this, 16)));
        LinearLayout createLabels = Ui.col((Context)this);
        LinearLayout.LayoutParams createSpace = new LinearLayout.LayoutParams(0, -2, 1.0f);
        createSpace.leftMargin = Ui.dp((Context)this, 10);
        create.addView((View)createLabels, createSpace);
        createLabels.addView(Ui.text((Context)this, "\u65b0\u5efa\u9879\u76ee", 13.5f, Ui.onAccentSoft, Ui.medium((Context)this)));
        createLabels.addView(Ui.text((Context)this, "\u6d4f\u89c8\u76ee\u5f55\u6216\u624b\u52a8\u8f93\u5165\u8def\u5f84", 11.0f, Ui.onAccentSoft, null));
        Ui.touchable(create);
        Ui.rippleRounded(create, 12, Ui.accent);
        create.setOnClickListener(v -> this.createProject());
        this.drawerList.addView(create);
        LinearLayout.LayoutParams createGap = Ui.fill();
        createGap.bottomMargin = Ui.dp((Context)this, 6);
        create.setLayoutParams(createGap);
        String needle = this.drawerQuery.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (JSONObject item : this.drawerProjects) {
            String path = ConversationContent.string(item, "path", "");
            String name = ConversationContent.string(item, "name", NativeHomeActivity.folder(path));
            if (path.isEmpty() || !needle.isEmpty() && !(name + " " + path).toLowerCase(Locale.ROOT).contains(needle)) continue;
            int count = 0;
            for (JSONObject thread : this.drawerThreads) {
                if (!path.equals(ConversationContent.string(thread, "cwd", ""))) continue;
                ++count;
            }
            LinearLayout row = Ui.row((Context)this);
            row.setGravity(16);
            row.setBackground(Ui.round(path.equals(this.filterCwd) ? Ui.accentSoft : 0, Ui.dp((Context)this, 12)));
            row.setPadding(Ui.dp((Context)this, 10), Ui.dp((Context)this, 9), Ui.dp((Context)this, 10), Ui.dp((Context)this, 9));
            boolean active = path.equals(this.filterCwd);
            ImageView icon = new ImageView((Context)this);
            icon.setImageResource(R.drawable.ic_folder);
            icon.setColorFilter(active ? Ui.onAccentSoft : Ui.muted);
            row.addView((View)icon, Ui.w(Ui.dp((Context)this, 17), Ui.dp((Context)this, 17)));
            LinearLayout labels = Ui.col((Context)this);
            LinearLayout.LayoutParams labelSpace = new LinearLayout.LayoutParams(0, -2, 1.0f);
            labelSpace.leftMargin = Ui.dp((Context)this, 10);
            row.addView((View)labels, labelSpace);
            labels.addView(Ui.text((Context)this, name, 13.5f, active ? Ui.onAccentSoft : Ui.text, Ui.medium((Context)this)));
            TextView meta = Ui.text((Context)this, NativeHomeActivity.join(" \u00b7 ", path, count + " \u4e2a\u4f1a\u8bdd"), 11.0f, active ? Ui.onAccentSoft : Ui.faint, null);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams metaSpace = Ui.fill();
            metaSpace.topMargin = Ui.dp((Context)this, 3);
            labels.addView((View)meta, metaSpace);
            Ui.touchable(row);
            if (!active) {
                Ui.rippleRounded(row, 12, Ui.text);
            }
            row.setOnClickListener(v -> {
                this.filterCwd = path;
                this.drawerTab = "threads";
                this.buildDrawer();
                this.refreshDrawerData();
            });
            row.setOnLongClickListener(v -> {
                this.selectProject(path, false);
                return true;
            });
            LinearLayout.LayoutParams space = Ui.fill();
            space.bottomMargin = Ui.dp((Context)this, 2);
            row.setLayoutParams(space);
            this.drawerList.addView(row);
            ++shown;
        }
        if (shown == 0) {
            Ui.empty(this.drawerList, R.drawable.ic_folder, needle.isEmpty() ? "\u8fd8\u6ca1\u6709\u9879\u76ee" : "\u6ca1\u6709\u5339\u914d\u7684\u9879\u76ee", needle.isEmpty() ? "\u70b9\u4e0a\u65b9\u300c\u65b0\u5efa\u9879\u76ee\u300d\u6dfb\u52a0\u5de5\u4f5c\u76ee\u5f55\u3002" : "\u6362\u4e2a\u5173\u952e\u8bcd\u8bd5\u8bd5\u3002");
        }
    }

    private void openThread(JSONObject item) {
        if (this.submitting || !this.turnId.isEmpty()) {
            NativeUi.toast((Activity)this, "\u8bf7\u5148\u7ed3\u675f\u5f53\u524d\u4efb\u52a1");
            return;
        }
        this.drawer.closeDrawers();
        this.loadThread(item.optString("id"));
    }

    private void quiet(String method, JSONObject params, Consumer<JSONObject> done) {
        if (method.startsWith("thread/") || method.startsWith("turn/")) method = "native/chat/" + method;
        if (method.startsWith("native/permissions/")) method = method.replace("native/permissions/", "native/chat/permissions/");
        try {
            params.put("engine", this.engine);
        }
        catch (JSONException jSONException) {
            // empty catch block
        }
        NativeEngine.call(method, params, r -> {
            if (!this.isFinishing()) {
                done.accept((JSONObject)r);
            }
        }, message -> {});
    }

    private View drawerHeader() {
        LinearLayout header = Ui.col((Context)this);
        header.setPadding(Ui.dp((Context)this, 20), Ui.dp((Context)this, 22), Ui.dp((Context)this, 20), Ui.dp((Context)this, 6));
        LinearLayout brand = Ui.row((Context)this);
        LinearLayout mark = new LinearLayout((Context)this);
        mark.setGravity(17);
        mark.setBackground(Ui.round(Ui.accentSoft, Ui.dp((Context)this, 13)));
        ImageView spark = new ImageView((Context)this);
        spark.setImageResource(R.drawable.ic_spark);
        spark.setColorFilter(Ui.onAccentSoft);
        mark.addView((View)spark, Ui.w(Ui.dp((Context)this, 20), Ui.dp((Context)this, 20)));
        brand.addView((View)mark, Ui.w(Ui.dp((Context)this, 40), Ui.dp((Context)this, 40)));
        LinearLayout names = Ui.col((Context)this);
        LinearLayout.LayoutParams nameSpace = new LinearLayout.LayoutParams(0, -2, 1.0f);
        nameSpace.leftMargin = Ui.dp((Context)this, 12);
        brand.addView((View)names, nameSpace);
        names.addView(Ui.text((Context)this, "Ai-XS", 17.0f, Ui.text, Ui.medium((Context)this)));
        names.addView(Ui.text((Context)this, this.versionName(), 11.5f, Ui.faint, null));
        header.addView(brand);
        Ui.gap(header, 16);
        LinearLayout track = Ui.row((Context)this);
        track.setBackground(Ui.round(Ui.cardHigh, Ui.dp((Context)this, 999)));
        track.setPadding(Ui.dp((Context)this, 4), Ui.dp((Context)this, 4), Ui.dp((Context)this, 4), Ui.dp((Context)this, 4));
        for (String id2 : ENGINE_IDS) {
            boolean active = id2.equals(this.engine);
            LinearLayout segment = new LinearLayout((Context)this);
            segment.setGravity(17);
            segment.setBackground(Ui.round(active ? Ui.accentSoft : 0, Ui.dp((Context)this, 999)));
            TextView label = Ui.text((Context)this, NativeUi.engineName(id2), 12.0f, active ? Ui.onAccentSoft : Ui.muted, active ? Ui.medium((Context)this) : null);
            label.setSingleLine(true);
            segment.addView(label);
            segment.setMinimumHeight(Ui.dp((Context)this, 32));
            Ui.touchable(segment);
            if (!active) {
                Ui.ripple(segment, Ui.muted);
            }
            segment.setOnClickListener(v -> {
                this.drawer.closeDrawers();
                this.switchEngine(id2);
            });
            track.addView((View)segment, new LinearLayout.LayoutParams(0, -2, 1.0f));
        }
        header.addView((View)track, Ui.fill());
        Ui.gap(header, 12);
        return header;
    }

    private View drawerFooter() {
        LinearLayout footer = Ui.col((Context)this);
        footer.setPadding(Ui.dp((Context)this, 12), Ui.dp((Context)this, 8), Ui.dp((Context)this, 12), Ui.dp((Context)this, 12));
        footer.addView(Ui.divider((Context)this, 12));
        Ui.gap(footer, 6);
        footer.addView(this.drawerRow(R.drawable.ic_tools, "工具与扩展", false, () -> {
            this.drawer.closeDrawers();
            this.startActivity(new Intent((Context)this, NativeToolsActivity.class).putExtra("engine", this.engine));
        }, false));
        footer.addView(this.drawerRow(R.drawable.ic_settings, "\u8bbe\u7f6e", false, () -> {
            this.drawer.closeDrawers();
            this.startActivity(new Intent((Context)this, RuntimeActivity.class));
        }, false));
        LinearLayout line = Ui.row((Context)this);
        line.setGravity(16);
        line.setPadding(Ui.dp((Context)this, 10), Ui.dp((Context)this, 8), 0, 0);
        View dot = new View((Context)this);
        dot.setBackground(Ui.round(this.runtimeReady() ? Ui.mint : Ui.faint, Ui.dp((Context)this, 3)));
        line.addView(dot, Ui.w(Ui.dp((Context)this, 6), Ui.dp((Context)this, 6)));
        TextView label = Ui.text((Context)this, this.runtimeReady() ? "\u5171\u4eab\u73af\u5883\u5df2\u5c31\u7eea" : "\u8fd0\u884c\u73af\u5883\u5f85\u521d\u59cb\u5316", 11.5f, Ui.faint, null);
        LinearLayout.LayoutParams labelSpace = new LinearLayout.LayoutParams(-1, -2);
        labelSpace.leftMargin = Ui.dp((Context)this, 8);
        line.addView((View)label, labelSpace);
        footer.addView(line);
        return footer;
    }

    private boolean runtimeReady() {
        return new RuntimeManager((Context)this, s -> {}).complete();
    }

    private LinearLayout drawerPrimary() {
        LinearLayout row = Ui.row((Context)this);
        row.setBackground(Ui.round(Ui.accent, Ui.dp((Context)this, 14)));
        row.setGravity(17);
        row.setMinimumHeight(Ui.dp((Context)this, 42));
        ImageView plus = new ImageView((Context)this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setColorFilter(Ui.onAccent);
        LinearLayout.LayoutParams plusSpace = Ui.w(Ui.dp((Context)this, 18), Ui.dp((Context)this, 18));
        plusSpace.rightMargin = Ui.dp((Context)this, 8);
        row.addView((View)plus, plusSpace);
        row.addView(Ui.text((Context)this, "\u65b0\u5efa\u5bf9\u8bdd", 13.5f, Ui.onAccent, Ui.medium((Context)this)));
        Ui.touchable(row);
        Ui.ripple(row, Ui.onAccent);
        row.setOnClickListener(v -> this.newThread());
        return row;
    }

    private TextView section(String label, int startPad) {
        TextView view = Ui.sectionLabel((Context)this, label);
        view.setPadding(Ui.dp((Context)this, startPad + 8), Ui.dp((Context)this, 6), 0, Ui.dp((Context)this, 6));
        return view;
    }

    private LinearLayout drawerRow(int icon, String label, boolean active, Runnable click, boolean accented) {
        LinearLayout row = Ui.row((Context)this);
        int bg = accented ? Ui.accentSoft : (active ? Ui.accentSoft : 0);
        row.setBackground(Ui.round(bg, Ui.dp((Context)this, 13)));
        row.setPadding(Ui.dp((Context)this, 12), 0, Ui.dp((Context)this, 12), 0);
        row.setMinimumHeight(Ui.dp((Context)this, 44));
        ImageView image = new ImageView((Context)this);
        image.setImageResource(icon);
        image.setColorFilter(accented || active ? Ui.accent : Ui.muted);
        row.addView((View)image, Ui.w(Ui.dp((Context)this, 18), Ui.dp((Context)this, 18)));
        TextView text = Ui.text((Context)this, label, 14.0f, accented ? Ui.accent : (active ? Ui.accent : Ui.text), accented || active ? Ui.medium((Context)this) : null);
        LinearLayout.LayoutParams textSpace = new LinearLayout.LayoutParams(-1, -2);
        textSpace.leftMargin = Ui.dp((Context)this, 12);
        row.addView((View)text, textSpace);
        Ui.touchable(row);
        if (!accented && !active) {
            Ui.ripple(row, Ui.text);
        }
        row.setOnClickListener(v -> click.run());
        return row;
    }

    private void showDisconnected() {
        this.status("\u8fd0\u884c\u73af\u5883\u5c1a\u672a\u521d\u59cb\u5316", Ui.warn, true);
        this.renderMessages(false);
        this.updateControls();
    }

    private void initializationHint() {
        new MaterialAlertDialogBuilder((Context)this).setTitle((CharSequence)"\u5c1a\u672a\u521d\u59cb\u5316\u8fd0\u884c\u73af\u5883").setMessage((CharSequence)"\u4f7f\u7528 Agent \u524d\uff0c\u8bf7\u5230\u8bbe\u7f6e\u4e2d\u7684\u8fd0\u884c\u73af\u5883\u5b8c\u6210\u521d\u59cb\u5316\u3002\u4e09\u4e2a Agent \u5171\u7528\u8fd9\u4e00\u4efd\u73af\u5883\u3002").setNegativeButton((CharSequence)"\u7a0d\u540e", null).setPositiveButton((CharSequence)"\u53bb\u8bbe\u7f6e", (d, w) -> this.startActivity(new Intent((Context)this, RuntimeActivity.class).putExtra("page", "environment"))).show();
    }

    private void startService() {
        if (!new RuntimeManager((Context)this, s -> {}).complete()) {
            this.initializationHint();
            return;
        }
        try {
            this.startForegroundService(new Intent((Context)this, RuntimeService.class).putExtra("operation", "start"));
            this.status("\u6b63\u5728\u542f\u52a8\u5171\u4eab Agent \u670d\u52a1\u2026", Ui.muted, true);
        }
        catch (Exception e) {
            NativeUi.error((Activity)this, e.getMessage());
        }
    }

    private void call(String method, JSONObject params, Consumer<JSONObject> done) {
        if (method.startsWith("thread/") || method.startsWith("turn/")) method = "native/chat/" + method;
        if (method.startsWith("native/permissions/")) method = method.replace("native/permissions/", "native/chat/permissions/");
        try {
            params.put("engine", this.engine);
        }
        catch (JSONException jSONException) {
            // empty catch block
        }
        NativeEngine.call(method, params, r -> {
            if (!this.isFinishing()) {
                try {
                    done.accept((JSONObject)r);
                }
                catch (Exception e) {
                    NativeUi.error((Activity)this, e.toString());
                }
            }
        }, message -> {
            this.submitting = false;
            this.loading = false;
            this.updateControls();
            this.statusIdle();
            NativeUi.error((Activity)this, message);
        });
    }

    private void applyModels(JSONObject r) {
        JSONArray data = r.optJSONArray("data");
        ArrayList<String> ids = new ArrayList<String>();
        this.modelMeta.clear();
        if (data != null) {
            for (int i = 0; i < data.length(); ++i) {
                JSONObject item = data.optJSONObject(i);
                String id2 = item.optString("model");
                if (id2.isEmpty()) id2 = item.optString("id");
                if (id2.isEmpty() || ids.contains(id2)) continue;
                ids.add(id2);
                this.modelMeta.put(id2, item);
            }
        }
        this.modelOptions.clear();
        this.modelOptions.addAll(ids);
        int at = ids.indexOf(this.model);
        if (at < 0 && !ids.isEmpty()) {
            at = 0;
            this.model = (String)ids.get(0);
        }
        this.normalizeEffort();
        this.updateModelCapabilities();
        if (this.modelLabel != null && at >= 0) {
            this.modelLabel.setText(NativeHomeActivity.shortModel((String)ids.get(at)));
        }
    }

    private void refresh() {
        if (this.loading || !NativeEngine.ready()) return;
        this.loading = true;
        final int generation = this.engineGeneration;
        final String owner = this.engine;
        this.readPermissionMode();
        JSONObject cached = this.viewCache.models(owner);
        if (cached != null) this.applyModels(cached);
        // This native endpoint reads local API configuration; no Agent CLI starts.
        NativeEngine.call("model/list", NativeUi.json("engine", owner), r -> {
            if (generation != this.engineGeneration) return;
            this.viewCache.models(owner, r);
            this.applyModels(r);
            this.loading = false;
            this.updateControls();
        }, message -> {
            if (generation != this.engineGeneration) return;
            this.loading = false;
            this.statusIdle();
            NativeUi.toast(this, message);
        });
        // History loads once and independently of the model callback.
        if (!this.threadId.isEmpty() && this.thread == null
                && !this.threadId.equals(this.pendingThread) && !this.threadId.equals(this.failedThread)) {
            this.loadThread(this.threadId);
        }
        NativeEngine.call("native/status", NativeUi.json(), v -> {
            if (generation != this.engineGeneration) return;
            JSONArray requests = v.optJSONArray("approvals");
            if (requests != null) {
                for (int i = 0; i < requests.length(); i++) this.showApproval(requests.optJSONObject(i));
            }
        }, message -> {});
    }

    private static String threadEngine(String id) {
        return id.startsWith("claude-") ? "claude" : id.startsWith("deepseek-") ? "deepseek" : "codex";
    }

    private void switchEngine(String next) {
        if (next.equals(this.engine)) return;
        if (this.submitting || !this.turnId.isEmpty()) {
            NativeUi.toast(this, "请先等待或停止当前任务，再切换 Agent");
            return;
        }
        if (this.threadId.isEmpty()) {
            this.activateEngine(next, null);
            return;
        }
        this.submitting = true;
        this.updateControls();
        String selected = this.threadId;
        String nextModel = this.getSharedPreferences("native-chat", 0).getString("model-" + next, "");
        NativeEngine.call("native/chat/switch", NativeUi.json("engine", next, "threadId", selected, "model", nextModel), r -> {
            this.submitting = false;
            if (!selected.equals(this.threadId)) return;
            if (r.optBoolean("threadMissing") || r.optJSONObject("thread") == null) {
                this.updateControls();
                NativeUi.toast(this, "当前会话已失效，请从会话列表重新打开");
                return;
            }
            this.activateEngine(next, r.optJSONObject("thread"));
            NativeUi.toast(this, "已切换到 " + NativeUi.engineName(next) + "，继续当前会话");
        }, message -> {
            this.submitting = false;
            this.updateControls();
            NativeUi.error(this, message);
        });
    }

    private void activateEngine(String next, JSONObject sharedThread) {
        this.persist();
        this.engine = next;
        ++this.engineGeneration;
        ++this.historyGeneration;
        this.pendingThread = "";
        this.failedThread = "";
        this.loading = false;
        this.followLatest = true;
        this.model = this.getSharedPreferences("native-chat", 0).getString("model-" + next, "");
        this.thread = sharedThread;
        if (sharedThread != null) this.threadId = sharedThread.optString("id");
        this.resumed = false;
        this.usage = sharedThread == null ? null : sharedThread.optJSONObject("usage");
        this.permissionMode = "ask";
        this.composer.setHint("输入任务，交给 " + NativeUi.engineName(next));
        if (this.engineLabel != null) this.engineLabel.setText(NativeUi.engineName(next));
        this.modelOptions.clear();
        this.modelMeta.clear();
        JSONObject cachedModels = this.viewCache.models(next);
        if (cachedModels != null) this.applyModels(cachedModels);
        if (this.modelLabel != null) this.modelLabel.setText(this.model.isEmpty() ? "选择模型" : shortModel(this.model));
        this.applyThreadView(sharedThread);
        this.refreshUsage();
        this.persist();
        this.buildDrawer();
        this.refresh();
    }

    private void newThread() {
        if (this.submitting || !this.turnId.isEmpty()) {
            NativeUi.toast((Activity)this, "\u8bf7\u5148\u7b49\u5f85\u6216\u505c\u6b62\u4efb\u52a1");
            return;
        }
        this.drawer.closeDrawers();
        this.threadId = "";
        ++this.historyGeneration;
        this.pendingThread = "";
        this.failedThread = "";
        this.readPermissionMode();
        this.expandedActivities.clear();
        this.autoExpandedActivities.clear();
        this.followLatest = true;
        this.thread = null;
        this.updateCollaborationChrome();
        this.resumed = false;
        this.usage = null;
        this.attachments.clear();
        this.quote = null;
        this.refreshQuote();
        this.composer.setText("");
        this.persist();
        this.renderMessages(false);
        this.refreshUsage();
        this.refreshDrawerData();
    }

    private void applyThreadView(JSONObject value) {
        try { value=CollaborationEvents.mergeSnapshot(this.thread,value); } catch(JSONException ignored) {}
        if(this.collaboration!=null)this.collaboration.replay(value);
        this.thread = value;
        if (value != null) mergeCachedCollaboration(value);
        if(value!=null)this.threadId=value.optString("id",this.threadId);
        if (this.thread != null) {
            if (this.thread.optJSONObject("usage") != null) this.usage = this.thread.optJSONObject("usage");
            this.cwd = ConversationContent.string(this.thread, "cwd", this.cwd);
            String savedModel = ConversationContent.string(this.thread, "model", "");
            if (!savedModel.isEmpty()) this.model = savedModel;
            this.normalizeEffort();
            this.updateModelCapabilities();
            if (this.modelLabel != null) {
                this.modelLabel.setText(NativeHomeActivity.shortModel(this.model));
            }
            this.updateSubtitle();
            if (this.projectPill != null) {
                TextView label = (TextView)this.projectPill.getChildAt(this.projectPill.getChildCount() - 1);
                label.setText(NativeHomeActivity.folder(this.cwd));
            }
        }
        this.renderMessages(false);
        this.updateCollaborationChrome();
        this.updateUsageRow();
    }

    /** Queue state changes must not re-layout the whole transcript or animate it. */
    private void applyQueueView(JSONObject value) {
        if (value == null || this.thread == null) return;
        if (!this.threadId.equals(value.optString("id", this.threadId))) return;
        String oldTurns = String.valueOf(this.thread.optJSONArray("turns"));
        try {
            ConversationSnapshots.applyQueue(this.thread, value);
            if (this.collaboration != null) this.collaboration.replay(this.thread);
            mergeCachedCollaboration(this.thread);
            if (!oldTurns.equals(String.valueOf(this.thread.optJSONArray("turns")))) {
                this.renderMessages(false);
                this.updateCollaborationChrome();
                this.updateUsageRow();
                return;
            }
        } catch (JSONException ignored) {
            return;
        }
        if (this.messageQueue != null) this.messageQueue.render();
        this.updateExecutionButton();
        this.updateStatusLine();
    }

    private void loadThread(String id2) {
        if (id2.equals(this.pendingThread)) return;
        if (!id2.equals(this.threadId)) {
            this.expandedActivities.clear();
        this.autoExpandedActivities.clear();
            this.followLatest = true;
        }
        final int generation = this.engineGeneration;
        final int request = ++this.historyGeneration;
        this.threadId = id2;
        this.pendingThread = id2;
        this.failedThread = "";
        this.resumed = false;
        JSONObject display = ConversationSnapshots.forLoad(this.thread, this.viewCache.thread(id2), id2);
        if (display != this.thread) {
            this.usage = this.viewCache.usage(id2);
            this.applyThreadView(display);
        } else if (display == null) this.renderMessages(false);
        NativeEngine.call("native/chat/thread/read", NativeUi.json("engine", this.engine, "threadId", id2, "cwd", this.cwd), r -> {
            if (generation != this.engineGeneration || request != this.historyGeneration || !id2.equals(this.threadId)) return;
            this.pendingThread = "";
            if (r.optBoolean("threadMissing")) {
                this.viewCache.forget(id2);
                this.threadId = "";
                this.thread = null;
                this.usage = null;
                this.failedThread = "";
                this.resumed = false;
                this.persist();
                this.readPermissionMode();
                this.renderMessages(false);
                this.statusIdle();
                NativeUi.toast(this, "上次会话已失效，已打开新对话");
                return;
            }
            JSONObject loaded = r.optJSONObject("thread");
            if (loaded != null && !loaded.optString("nativeNotice").isEmpty())
                NativeUi.toast(this, loaded.optString("nativeNotice"));
            if (loaded != null && !loaded.optString("engine", this.engine).equals(this.engine)) {
                this.activateEngine(loaded.optString("engine"), loaded);
            } else {
                this.applyThreadView(loaded);
            }
            this.readPermissionMode();
            this.persist();
            this.refreshUsage();
            this.statusIdle();
        }, message -> {
            if (generation != this.engineGeneration || request != this.historyGeneration || !id2.equals(this.threadId)) return;
            this.pendingThread = "";
            this.failedThread = id2;
            if (this.thread == null) this.renderMessages(false);
            this.statusIdle();
            NativeUi.toast(this, "会话读取失败：" + message);
        });
    }

    private void sessions() {
        this.drawer.closeDrawers();
        this.listSessions(r -> {
            JSONArray data = r.optJSONArray("data");
            this.sheet(sheet -> {
                LinearLayout header = Ui.row((Context)this);
                header.setPadding(Ui.dp((Context)this, 20), Ui.dp((Context)this, 4), Ui.dp((Context)this, 12), Ui.dp((Context)this, 8));
                header.addView((View)Ui.title((Context)this, "\u4f1a\u8bdd"), new LinearLayout.LayoutParams(0, -2, 1.0f));
                header.addView(Ui.iconButton((Context)this, R.drawable.ic_close, "\u5173\u95ed", () -> ((BottomSheetDialog)sheet).dismiss()));
                this.content.addView(header);
                final ArrayList<JSONObject> all = new ArrayList<JSONObject>();
                if (data != null) {
                    for (int i = 0; i < data.length(); ++i) {
                        all.add(data.optJSONObject(i));
                    }
                }
                ScrollView scroll = new ScrollView((Context)this);
                scroll.setVerticalScrollBarEnabled(false);
                final LinearLayout list = Ui.col((Context)this);
                list.setPadding(Ui.dp((Context)this, 14), 0, Ui.dp((Context)this, 14), Ui.dp((Context)this, 24));
                scroll.addView(list);
                this.content.addView((View)scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
                this.renderSessionList(list, all, "");
                EditText search = new EditText((Context)this);
                search.setHint("\u641c\u7d22\u4f1a\u8bdd");
                search.setSingleLine(true);
                search.setBackground(null);
                search.setTextSize(14.0f);
                search.setTextColor(Ui.text);
                search.setHintTextColor(Ui.faint);
                search.setInputType(1);
                LinearLayout searchBox = Ui.row((Context)this);
                searchBox.setBackground(Ui.round(Ui.cardHigh, Ui.dp((Context)this, 14)));
                searchBox.setPadding(Ui.dp((Context)this, 6), 0, Ui.dp((Context)this, 10), 0);
                ImageView lens = new ImageView((Context)this);
                lens.setImageResource(R.drawable.ic_search);
                lens.setColorFilter(Ui.faint);
                searchBox.addView((View)lens, Ui.w(Ui.dp((Context)this, 16), Ui.dp((Context)this, 16)));
                LinearLayout.LayoutParams searchSpace = new LinearLayout.LayoutParams(0, Ui.dp((Context)this, 44), 1.0f);
                searchSpace.leftMargin = Ui.dp((Context)this, 6);
                searchBox.addView((View)search, searchSpace);
                LinearLayout.LayoutParams boxSpace = new LinearLayout.LayoutParams(-1, Ui.dp((Context)this, 44));
                boxSpace.setMargins(Ui.dp((Context)this, 20), 0, Ui.dp((Context)this, 20), Ui.dp((Context)this, 10));
                this.content.addView((View)searchBox, this.content.indexOfChild(scroll), boxSpace);
                search.addTextChangedListener(new TextWatcher(){

                    @Override
                    public void afterTextChanged(Editable s) {
                        NativeHomeActivity.this.renderSessionList(list, all, s.toString());
                    }

                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                    }
                });
            }, 0.86f);
        });
    }

    private void renderSessionList(LinearLayout list, ArrayList<JSONObject> all, String query) {
        list.removeAllViews();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        ArrayList<JSONObject> ordered = new ArrayList<JSONObject>(all);
        ordered.sort((left, right) -> Long.compare(ConversationContent.stampMillis(ConversationContent.stamp(right, "updatedAt", "createdAt")), ConversationContent.stampMillis(ConversationContent.stamp(left, "updatedAt", "createdAt"))));
        java.util.LinkedHashMap<String, ArrayList<JSONObject>> groups = new java.util.LinkedHashMap<String, ArrayList<JSONObject>>();
        for (JSONObject item : ordered) {
            String title = ConversationContent.title(item);
            String preview = ConversationContent.string(item, "preview", "");
            String path = ConversationContent.string(item, "cwd", "");
            if (!needle.isEmpty() && !(title + " " + preview + " " + path).toLowerCase(Locale.ROOT).contains(needle)) continue;
            ArrayList<JSONObject> bucket = groups.get(path);
            if (bucket == null) {
                bucket = new ArrayList<JSONObject>();
                groups.put(path, bucket);
            }
            bucket.add(item);
        }
        for (String path : groups.keySet()) {
            ArrayList<JSONObject> bucket = groups.get(path);
            TextView group = Ui.sectionLabel((Context)this, NativeHomeActivity.folder(path.isEmpty() ? "未设置目录" : path) + " \u00b7 " + bucket.size() + " \u4e2a\u4f1a\u8bdd");
            group.setPadding(Ui.dp((Context)this, 8), Ui.dp((Context)this, 12), 0, Ui.dp((Context)this, 6));
            list.addView(group);
            for (JSONObject item : bucket) {
                list.addView(this.sessionRow(item, ConversationContent.title(item)));
                ++shown;
            }
        }
        if (shown == 0) {
            Ui.empty(list, R.drawable.ic_history, needle.isEmpty() ? "\u8fd8\u6ca1\u6709\u4f1a\u8bdd" : "\u6ca1\u6709\u5339\u914d\u7684\u4f1a\u8bdd", needle.isEmpty() ? "\u53d1\u9001\u7b2c\u4e00\u6761\u4efb\u52a1\u540e\uff0c\u8fd9\u91cc\u4f1a\u4fdd\u5b58\u6bcf\u6b21\u5bf9\u8bdd\u3002" : "\u6362\u4e2a\u5173\u952e\u8bcd\u8bd5\u8bd5\u3002");
        }
    }

    private LinearLayout sessionRow(JSONObject item, String title) {
        LinearLayout row = Ui.row((Context)this);
        row.setBackground(Ui.round((Context)this, Ui.card, 16.0f, 0.8f, Ui.lineSoft));
        row.setPadding(Ui.dp((Context)this, 14), Ui.dp((Context)this, 12), Ui.dp((Context)this, 12), Ui.dp((Context)this, 12));
        LinearLayout.LayoutParams rowSpace = new LinearLayout.LayoutParams(-1, -2);
        rowSpace.bottomMargin = Ui.dp((Context)this, 8);
        row.setLayoutParams(rowSpace);
        LinearLayout labels = Ui.col((Context)this);
        row.addView((View)labels, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView name = Ui.text((Context)this, title, 14.5f, item.optString("id").equals(this.threadId) ? Ui.accent : Ui.text, Ui.medium((Context)this));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        String meta = NativeHomeActivity.join(" \u00b7 ", NativeUi.engineName(item.optString("engine", threadEngine(item.optString("id")))), NativeHomeActivity.shortModel(ConversationContent.string(item, "model", "\u6a21\u578b\u672a\u5b9a")), NativeHomeActivity.folder(ConversationContent.string(item, "cwd", this.cwd)));
        TextView note = Ui.text((Context)this, meta, 11.5f, Ui.faint, null);
        note.setSingleLine(true);
        note.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
        noteSpace.topMargin = Ui.dp((Context)this, 3);
        labels.addView((View)note, noteSpace);
        Ui.touchable(row);
        Ui.ripple(row, Ui.text);
        row.setOnClickListener(v -> {
            if (!this.turnId.isEmpty()) {
                NativeUi.toast((Activity)this, "\u8bf7\u5148\u7ed3\u675f\u5f53\u524d\u4efb\u52a1");
                return;
            }
            this.openThread(item);
            this.dialogDismiss();
        });
        row.setOnLongClickListener(v -> {
            this.sessionActions(item);
            return true;
        });
        return row;
    }

    private void dialogDismiss() {
        if (this.currentSheet != null) {
            this.currentSheet.dismiss();
            this.currentSheet = null;
        }
    }

    private void sheet(Consumer<BottomSheetDialog> fill, double heightRatio) {
        View view;
        if (this.currentSheet != null) {
            this.currentSheet.dismiss();
            this.currentSheet = null;
        }
        BottomSheetDialog sheet = new BottomSheetDialog((Context)this);
        LinearLayout column = Ui.col((Context)this);
        column.setPadding(0, Ui.dp((Context)this, 8), 0, 0);
        View handle = new View((Context)this);
        handle.setBackground(Ui.round(Ui.line, Ui.dp((Context)this, 2)));
        LinearLayout.LayoutParams handleSpace = new LinearLayout.LayoutParams(Ui.dp((Context)this, 36), Ui.dp((Context)this, 4));
        handleSpace.gravity = 1;
        handleSpace.bottomMargin = Ui.dp((Context)this, 8);
        column.addView(handle, handleSpace);
        sheet.setContentView((View)column);
        if (sheet.getWindow() != null) {
            sheet.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        this.content = column;
        this.currentSheet = sheet;
        fill.accept(sheet);
        if (heightRatio > 0.0) {
            sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        }
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.show();
        if (heightRatio > 0.0 && sheet.getWindow() != null && (view = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet)) != null) {
            int height;
            view.getLayoutParams().height = height = (int)((double)this.getResources().getDisplayMetrics().heightPixels * heightRatio);
            view.requestLayout();
        }
    }

    private void forgetSession(JSONObject value) {
        String id = value.optString("id");
        this.viewCache.forget(id);
        String path = ConversationContent.string(value, "cwd", this.cwd);
        SharedPreferences prefs = this.getSharedPreferences("native-chat", 0);
        String common = "conversation-" + path.hashCode();
        if (id.equals(prefs.getString(common, ""))) prefs.edit().remove(common).apply();
        for (String agent : ENGINE_IDS) {
            String key = "thread-" + agent + "-" + path.hashCode();
            if (id.equals(prefs.getString(key, ""))) prefs.edit().remove(key).apply();
        }
    }

    private void sessionActions(JSONObject t) {
        String id2 = t.optString("id");
        this.sheet(sheet -> {
            LinearLayout box = this.content;
            box.setPadding(0, 0, 0, Ui.dp((Context)this, 20));
            TextView heading = Ui.text((Context)this, ConversationContent.title(t), 15.0f, Ui.text, Ui.medium((Context)this));
            LinearLayout.LayoutParams headSpace = new LinearLayout.LayoutParams(-1, -2);
            headSpace.setMargins(Ui.dp((Context)this, 20), Ui.dp((Context)this, 6), Ui.dp((Context)this, 20), Ui.dp((Context)this, 10));
            box.addView((View)heading, headSpace);
            LinearLayout actions = Ui.col((Context)this);
            actions.setPadding(Ui.dp((Context)this, 10), 0, Ui.dp((Context)this, 10), 0);
            box.addView((View)actions, Ui.fill());
            actions.addView(this.drawerRow(R.drawable.ic_file, "\u91cd\u547d\u540d", false, () -> {
                sheet.dismiss();
                NativeUi.prompt((Activity)this, "\u4f1a\u8bdd\u540d\u79f0", "\u540d\u79f0", ConversationContent.string(t, "name", ""), false, name -> this.call("thread/name/set", NativeUi.json("threadId", id2, "name", name), r -> {
                    this.viewCache.forget(id2);
                    NativeUi.toast((Activity)this, "\u5df2\u91cd\u547d\u540d");
                    if (id2.equals(this.threadId)) {
                        this.loadThread(id2);
                    }
                    this.sessions();
                }));
            }, false));
            actions.addView(this.drawerRow(R.drawable.ic_copy, "\u5206\u53c9\u4f1a\u8bdd", false, () -> {
                sheet.dismiss();
                this.call("thread/fork", NativeUi.json("threadId", id2), r -> this.loadThread(r.optJSONObject("thread").optString("id")));
            }, false));
            actions.addView(this.drawerRow(R.drawable.ic_history, "\u5f52\u6863\u4f1a\u8bdd", false, () -> {
                sheet.dismiss();
                this.call("thread/archive", NativeUi.json("threadId", id2), r -> {
                    this.forgetSession(t);
                    if (id2.equals(this.threadId)) {
                        this.newThread();
                    }
                    NativeUi.toast((Activity)this, "\u5df2\u5f52\u6863");
                });
            }, false));
            {
                actions.addView(this.drawerRow(R.drawable.ic_close, "\u5220\u9664\u4f1a\u8bdd", false, () -> {
                    new MaterialAlertDialogBuilder((Context)this)
                        .setTitle((CharSequence)"\u5220\u9664\u4f1a\u8bdd\uff1f")
                        .setMessage((CharSequence)"\u5c06\u5220\u9664\u672c\u4f1a\u8bdd\u7684\u6d88\u606f\u3001\u539f\u751f Agent \u5386\u53f2\u548c\u5173\u8054\u6267\u884c\u6570\u636e\u3002\u9879\u76ee\u6587\u4ef6\u53ca\u72ec\u7acb\u5206\u53c9\u4f1a\u8bdd\u4fdd\u7559\u3002")
                        .setNegativeButton((CharSequence)"\u53d6\u6d88", null)
                        .setPositiveButton((CharSequence)"\u5220\u9664", (dialog, which) -> {
                            sheet.dismiss();
                            this.call("thread/delete", NativeUi.json("threadId", id2), r -> {
                                this.forgetSession(t);
                                for (int i = this.drawerThreads.size() - 1; i >= 0; --i) {
                                    if (id2.equals(this.drawerThreads.get(i).optString("id"))) this.drawerThreads.remove(i);
                                }
                                if (id2.equals(this.threadId)) {
                                    this.newThread();
                                } else {
                                    this.renderDrawer();
                                    this.refreshDrawerData();
                                }
                                NativeUi.toast((Activity)this, "\u4f1a\u8bdd\u5df2\u5220\u9664");
                            });
                        }).show();
                }, false));
            }
        }, 0.0);
    }

    private void chooseModel() {
        if(this.collaboration!=null&&this.collaboration.enabled()){this.collaboration.memberField("model");return;}
        if (this.modelOptions.isEmpty()) {
            NativeUi.toast((Activity)this, "\u8bf7\u5148\u8fde\u63a5 Agent \u670d\u52a1\u5e76\u914d\u7f6e\u53ef\u7528\u6a21\u578b");
            return;
        }
        this.sheet(sheet -> {
            this.content.setPadding(0, 0, 0, Ui.dp((Context)this, 12));
            TextView heading = Ui.text((Context)this, "\u9009\u62e9\u6a21\u578b", 15.0f, Ui.text, Ui.medium((Context)this));
            LinearLayout.LayoutParams headSpace = new LinearLayout.LayoutParams(-1, -2);
            headSpace.setMargins(Ui.dp((Context)this, 20), Ui.dp((Context)this, 6), Ui.dp((Context)this, 20), Ui.dp((Context)this, 8));
            this.content.addView((View)heading, headSpace);
            ScrollView scroll = new ScrollView((Context)this);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout list = Ui.col((Context)this);
            list.setPadding(Ui.dp((Context)this, 10), 0, Ui.dp((Context)this, 10), 0);
            scroll.addView(list);
            this.content.addView((View)scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
            for (String id2 : this.modelOptions) {
                JSONObject info = this.modelMeta.get(id2);
                String title = info == null ? id2 : info.optString("displayName", id2);
                if (title.isEmpty()) title = id2;
                String note = this.modelCapabilityNote(id2);
                list.addView(this.choiceRow(title, id2.equals(this.model), () -> {
                    this.model = id2;
                    this.normalizeEffort();
                    this.updateModelCapabilities();
                    if (!this.modelSupportsImages(id2)) {
                        this.attachments.clear();
                        this.updateAttach();
                    }
                    this.modelLabel.setText(NativeHomeActivity.shortModel(id2));
                    this.persist();
                    sheet.dismiss();
                }, note));
            }
        }, 0.6);
    }

    private void readPermissionMode() {
        if (!NativeEngine.ready()) {
            return;
        }
        String ownerEngine = this.engine;
        String ownerThread = this.threadId;
        NativeEngine.call("native/chat/permissions/read", NativeUi.json("engine", this.engine, "threadId", this.threadId), r -> {
            if (!ownerEngine.equals(this.engine) || !ownerThread.equals(this.threadId)) {
                return;
            }
            this.permissionMode = r.optString("mode", "ask");
            this.updatePermissionPill();
        }, m -> {});
    }

    private void choosePermissionMode() {
        if(this.collaboration!=null&&this.collaboration.enabled()){this.collaboration.memberField("permission");return;}
        if (this.submitting || !this.turnId.isEmpty()) {
            NativeUi.toast((Activity)this, "\u8bf7\u5148\u7b49\u5f85\u6216\u505c\u6b62\u4efb\u52a1\uff0c\u518d\u5207\u6362\u6743\u9650\u6a21\u5f0f");
            return;
        }
        if (!NativeEngine.ready()) {
            NativeUi.toast((Activity)this, "\u8bf7\u5148\u8fde\u63a5 Agent \u670d\u52a1");
            return;
        }
        String[][] options = this.permissionOptions();
        this.sheet(sheet -> {
            this.content.setPadding(0, 0, 0, Ui.dp((Context)this, 18));
            LinearLayout header = Ui.row((Context)this);
            header.setGravity(16);
            header.setPadding(Ui.dp((Context)this, 20), Ui.dp((Context)this, 4), Ui.dp((Context)this, 12), Ui.dp((Context)this, 2));
            header.addView((View)Ui.title((Context)this, NativeUi.engineName(this.engine) + " \u6743\u9650"), new LinearLayout.LayoutParams(0, -2, 1.0f));
            header.addView(Ui.iconButton((Context)this, R.drawable.ic_close, "\u5173\u95ed", () -> ((BottomSheetDialog)sheet).dismiss()));
            this.content.addView(header);
            TextView note = Ui.text((Context)this, this.permissionMechanism(), 12.0f, Ui.muted, null);
            note.setLineSpacing(Ui.dp((Context)this, 3), 1.0f);
            Ui.pad(note, 20, 2, 20, 10);
            this.content.addView(note);
            LinearLayout list = Ui.col((Context)this);
            list.setPadding(Ui.dp((Context)this, 10), 0, Ui.dp((Context)this, 10), 0);
            this.content.addView(list);
            for (String[] option : options) {
                list.addView(this.choiceRow(option[1], option[0].equals(this.permissionMode), () -> {
                    sheet.dismiss();
                    Runnable save = () -> this.call("native/permissions/set", NativeUi.json("threadId", this.threadId, "mode", option[0]), r -> {
                        this.permissionMode = option[0];
                        this.updatePermissionPill();
                        NativeUi.toast((Activity)this, NativeUi.engineName(this.engine) + " \u6743\u9650\u5df2\u66f4\u65b0\uff0c\u4e0b\u4e00\u6b21\u4efb\u52a1\u751f\u6548");
                    });
                    if (option[0].equals("auto") && !this.permissionMode.equals("auto")) {
                        new MaterialAlertDialogBuilder((Context)this).setTitle((CharSequence)("\u5141\u8bb8 " + NativeUi.engineName(this.engine) + " \u81ea\u52a8\u6267\u884c\uff1f")).setMessage((CharSequence)this.permissionImpact()).setNegativeButton((CharSequence)"\u53d6\u6d88", null).setPositiveButton((CharSequence)"\u542f\u7528", (d, w) -> save.run()).show();
                    } else {
                        save.run();
                    }
                }, option[2]));
            }
            TextView boundary = Ui.text((Context)this, "Android \u7cfb\u7edf\u6743\u9650\u4e0e\u65e0\u969c\u788d\u6388\u6743\u59cb\u7ec8\u7531\u7cfb\u7edf\u7ba1\u7406\uff1b\u6b64\u8bbe\u7f6e\u53ea\u5f71\u54cd Agent \u7684\u5de5\u5177\u6279\u51c6\u65b9\u5f0f\u3002", 11.5f, Ui.faint, null);
            Ui.pad(boundary, 20, 12, 20, 0);
            this.content.addView(boundary);
        }, 0.0);
    }

    private String[][] permissionOptions() {
        if (this.engine.equals("claude")) {
            return new String[][]{{"ask", "\u6bcf\u6b21\u786e\u8ba4", "\u5de5\u5177\u8c03\u7528\u524d\u7531\u4f60\u6279\u51c6\uff08Claude Code \u624b\u52a8\u6a21\u5f0f\uff09"}, {"auto", "\u81ea\u52a8\u63a5\u53d7", "\u5de5\u5177\u8c03\u7528\u76f4\u63a5\u6267\u884c\uff0c\u4e0d\u518d\u5f39\u51fa\u6279\u51c6"}};
        }
        if (this.engine.equals("deepseek")) {
            return new String[][]{{"ask", "\u8be2\u95ee\u6743\u9650", "\u6267\u884c\u547d\u4ee4\u6216\u4fee\u6539\u6587\u4ef6\u524d\u8be2\u95ee\uff08approval = ask\uff09"}, {"auto", "\u81ea\u52a8\u6267\u884c", "\u547d\u4ee4\u4e0e\u6587\u4ef6\u6539\u52a8\u76f4\u63a5\u6267\u884c\uff08approval = never\uff09"}};
        }
        return new String[][]{{"ask", "\u6309\u9700\u6279\u51c6", "\u5de5\u5177\u8c03\u7528\u524d\u5411\u4f60\u8bf7\u6c42\u6279\u51c6\uff08approvalPolicy = on-request\uff09"}, {"auto", "\u81ea\u52a8\u6279\u51c6", "\u4e0d\u518d\u8bf7\u6c42\u6279\u51c6\uff08approvalPolicy = never\uff09"}};
    }

    private String permissionShort() {
        for (String[] option : this.permissionOptions()) {
            if (!option[0].equals(this.permissionMode)) continue;
            return option[1];
        }
        return this.permissionOptions()[0][1];
    }

    private String permissionMechanism() {
        if (this.engine.equals("claude")) {
            return "Claude Code \u7528\u81ea\u5df1\u7684\u6743\u9650\u6a21\u5f0f\u8fd0\u884c\uff1a\u624b\u52a8\u6a21\u5f0f\u4e0b\u6bcf\u6b21\u5de5\u5177\u8c03\u7528\u90fd\u8981\u4f60\u786e\u8ba4\uff0c\u81ea\u52a8\u6a21\u5f0f\u5219\u76f4\u63a5\u63a5\u53d7\u3002";
        }
        if (this.engine.equals("deepseek")) {
            return "DeepSeek \u901a\u8fc7\u81ea\u8eab\u7684\u6279\u51c6\u7b56\u7565\u8fd0\u884c\uff1aask \u4f1a\u5728\u6267\u884c\u524d\u8bf7\u6c42\u4f60\u540c\u610f\uff0cnever \u5219\u4e0d\u8be2\u95ee\u3002";
        }
        return "Codex \u4f7f\u7528 approvalPolicy \u63a7\u5236\u6279\u51c6\uff1aon-request \u4f1a\u8bf7\u6c42\u4f60\u786e\u8ba4\uff0cnever \u5219\u76f4\u63a5\u6267\u884c\u3002\u6c99\u7bb1\u59cb\u7ec8\u4e3a\u672c\u673a\u5b8c\u5168\u8bbf\u95ee\uff0c\u4e0e\u6279\u51c6\u7b56\u7565\u76f8\u4e92\u72ec\u7acb\u3002";
    }

    private String permissionImpact() {
        if (this.engine.equals("claude")) {
            return "Claude Code \u5c06\u81ea\u52a8\u63a5\u53d7\u5de5\u5177\u8c03\u7528\uff0c\u53ef\u5728\u9879\u76ee\u76ee\u5f55\u5185\u8bfb\u5199\u6587\u4ef6\u3001\u8fd0\u884c\u547d\u4ee4\uff0c\u5e76\u8c03\u7528\u5df2\u6388\u6743\u7684\u624b\u673a\u5de5\u5177\u3002";
        }
        if (this.engine.equals("deepseek")) {
            return "DeepSeek \u5c06\u76f4\u63a5\u6267\u884c\u547d\u4ee4\u4e0e\u6587\u4ef6\u6539\u52a8\uff0c\u4e0d\u518d\u9010\u6b21\u8be2\u95ee\u3002";
        }
        return "Codex \u5c06\u76f4\u63a5\u6267\u884c\u547d\u4ee4\u4e0e\u6587\u4ef6\u6539\u52a8\uff0c\u4e0d\u518d\u9010\u6b21\u8be2\u95ee\u3002";
    }

    private void updatePermissionPill() {
        if (this.permissionLabel == null) {
            return;
        }
        this.permissionLabel.setText(this.collaboration!=null&&this.collaboration.enabled()?"成员权限":this.permissionShort());
    }

    private void chooseEffort() {
        if(this.collaboration!=null&&this.collaboration.enabled()){this.collaboration.memberField("effort");return;}
        ArrayList<String> supported = this.supportedEfforts(this.model);
        if (supported.isEmpty()) {
            NativeUi.toast((Activity)this, "当前模型没有可配置的思考强度");
            return;
        }
        String[] values = supported.toArray(new String[0]);
        String[] labels = new String[values.length];
        String[] notes = new String[values.length];
        for (int i = 0; i < values.length; ++i) {
            labels[i] = NativeHomeActivity.effortTitle(values[i]);
            notes[i] = NativeHomeActivity.effortNote(values[i]);
        }
        this.sheet(sheet -> {
            this.content.setPadding(0, 0, 0, Ui.dp((Context)this, 14));
            TextView heading = Ui.text((Context)this, "\u601d\u8003\u5f3a\u5ea6", 15.0f, Ui.text, Ui.medium((Context)this));
            LinearLayout.LayoutParams headSpace = new LinearLayout.LayoutParams(-1, -2);
            headSpace.setMargins(Ui.dp((Context)this, 20), Ui.dp((Context)this, 6), Ui.dp((Context)this, 20), Ui.dp((Context)this, 10));
            this.content.addView((View)heading, headSpace);
            LinearLayout list = Ui.col((Context)this);
            list.setPadding(Ui.dp((Context)this, 10), 0, Ui.dp((Context)this, 10), 0);
            this.content.addView(list);
            for (int i = 0; i < values.length; ++i) {
                String value = values[i];
                list.addView(this.choiceRow(labels[i] + " \u00b7 " + value, value.equals(this.effort), () -> {
                    this.effort = value;
                    this.effortLabel.setText(this.collaboration!=null&&this.collaboration.enabled()?"成员配置":this.effortName());
                    this.persist();
                    sheet.dismiss();
                }, notes[i]));
            }
        }, 0.0);
    }

    private LinearLayout choiceRow(String label, boolean selected, Runnable click) {
        return this.choiceRow(label, selected, click, null);
    }

    private LinearLayout choiceRow(String label, boolean selected, Runnable click, String note) {
        LinearLayout row = Ui.row((Context)this);
        row.setBackground(Ui.round(selected ? Ui.accentSoft : 0, Ui.dp((Context)this, 12)));
        row.setPadding(Ui.dp((Context)this, 12), Ui.dp((Context)this, 11), Ui.dp((Context)this, 12), Ui.dp((Context)this, 11));
        LinearLayout.LayoutParams rowSpace = new LinearLayout.LayoutParams(-1, -2);
        rowSpace.bottomMargin = Ui.dp((Context)this, 2);
        row.setLayoutParams(rowSpace);
        LinearLayout labels = Ui.col((Context)this);
        row.addView((View)labels, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView name = Ui.text((Context)this, label, 14.0f, selected ? Ui.onAccentSoft : Ui.text, selected ? Ui.medium((Context)this) : null);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        if (note != null) {
            TextView sub = Ui.text((Context)this, note, 11.5f, selected ? Ui.onAccentSoft : Ui.faint, null);
            LinearLayout.LayoutParams subSpace = new LinearLayout.LayoutParams(-1, -2);
            subSpace.topMargin = Ui.dp((Context)this, 3);
            labels.addView((View)sub, subSpace);
        }
        if (selected) {
            ImageView check = new ImageView((Context)this);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(Ui.accent);
            row.addView((View)check, Ui.w(Ui.dp((Context)this, 18), Ui.dp((Context)this, 18)));
        }
        Ui.touchable(row);
        Ui.ripple(row, Ui.text);
        row.setOnClickListener(v -> click.run());
        return row;
    }

    private void chooseAgent() {
        if(this.collaboration!=null&&this.collaboration.enabled()){this.collaboration.configure(false);return;}
        this.sheet(sheet -> {
            this.content.setPadding(0, 0, 0, Ui.dp((Context)this, 14));
            TextView heading = Ui.text((Context)this, "\u5207\u6362 Agent", 15.0f, Ui.text, Ui.medium((Context)this));
            LinearLayout.LayoutParams headSpace = new LinearLayout.LayoutParams(-1, -2);
            headSpace.setMargins(Ui.dp((Context)this, 20), Ui.dp((Context)this, 6), Ui.dp((Context)this, 20), Ui.dp((Context)this, 10));
            this.content.addView((View)heading, headSpace);
            LinearLayout list = Ui.col((Context)this);
            list.setPadding(Ui.dp((Context)this, 10), 0, Ui.dp((Context)this, 10), 0);
            this.content.addView(list);
            String[] notes = new String[]{"OpenAI Codex CLI", "Anthropic Claude Code", "DeepSeek \u539f\u751f\u63a5\u53e3"};
            for (int i = 0; i < ENGINE_IDS.length; ++i) {
                String id2 = ENGINE_IDS[i];
                list.addView(this.choiceRow(NativeUi.engineName(id2), id2.equals(this.engine), () -> {
                    sheet.dismiss();
                    this.switchEngine(id2);
                }, notes[i]));
            }
        }, 0.0);
    }

    private void projects() {
        if (this.submitting || !this.turnId.isEmpty()) {
            NativeUi.toast((Activity)this, "\u8bf7\u5148\u7b49\u5f85\u6216\u505c\u6b62\u5f53\u524d\u4efb\u52a1\uff0c\u518d\u5207\u6362\u9879\u76ee");
            return;
        }
        this.quiet("projects/list", NativeUi.json(new Object[0]), r -> {
            ArrayList<String[]> items = new ArrayList<String[]>();
            JSONArray data = r.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); ++i) {
                    String path;
                    JSONObject item = data.optJSONObject(i);
                    if (item == null || (path = ConversationContent.string(item, "path", "")).isEmpty()) continue;
                    items.add(new String[]{path, ConversationContent.string(item, "name", NativeHomeActivity.folder(path))});
                }
            }
            ArrayList<String> recent = new ArrayList<String>();
            for (JSONObject thread : this.drawerThreads) {
                String path = ConversationContent.string(thread, "cwd", "");
                if (path.isEmpty() || path.equals(this.cwd) || recent.contains(path) || !items.stream().noneMatch(entry -> entry[0].equals(path))) continue;
                recent.add(path);
            }
            this.sheet(sheet -> {
                this.content.setPadding(0, 0, 0, Ui.dp((Context)this, 16));
                LinearLayout header = Ui.row((Context)this);
                header.setGravity(16);
                header.setPadding(Ui.dp((Context)this, 20), Ui.dp((Context)this, 4), Ui.dp((Context)this, 12), Ui.dp((Context)this, 2));
                header.addView((View)Ui.title((Context)this, "\u9009\u62e9\u9879\u76ee"), new LinearLayout.LayoutParams(0, -2, 1.0f));
                header.addView(Ui.iconButton((Context)this, R.drawable.ic_close, "\u5173\u95ed", () -> ((BottomSheetDialog)sheet).dismiss()));
                this.content.addView(header);
                TextView note = Ui.text((Context)this, "\u65b0\u4efb\u52a1\u5c06\u5728\u6240\u9009\u76ee\u5f55\u4e0b\u5f00\u59cb\u3002", 12.0f, Ui.muted, null);
                Ui.pad(note, 20, 0, 20, 8);
                this.content.addView(note);
                ScrollView scroll = new ScrollView((Context)this);
                scroll.setVerticalScrollBarEnabled(false);
                LinearLayout list = Ui.col((Context)this);
                list.setPadding(Ui.dp((Context)this, 10), 0, Ui.dp((Context)this, 10), Ui.dp((Context)this, 8));
                scroll.addView(list);
                this.content.addView((View)scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
                if (!items.isEmpty()) {
                    list.addView(this.projectSheetSection("\u9879\u76ee"));
                }
                for (String[] item : items) {
                    list.addView(this.projectSheetRow(item[1], item[0], item[0].equals(this.cwd), () -> {
                        sheet.dismiss();
                        this.selectProject(item[0], true);
                    }));
                }
                if (!recent.isEmpty()) {
                    list.addView(this.projectSheetSection("\u6700\u8fd1\u4f7f\u7528"));
                    for (String path : recent) {
                        list.addView(this.projectSheetRow(NativeHomeActivity.folder(path), path, path.equals(this.cwd), () -> {
                            sheet.dismiss();
                            this.selectProject(path, true);
                        }));
                    }
                }
                TextView browse = Ui.text((Context)this, "\u6d4f\u89c8\u5176\u4ed6\u76ee\u5f55\u2026", 13.5f, Ui.accent, Ui.medium((Context)this));
                browse.setPadding(Ui.dp((Context)this, 12), Ui.dp((Context)this, 14), Ui.dp((Context)this, 12), Ui.dp((Context)this, 12));
                Ui.touchable(browse);
                Ui.rippleRounded(browse, 12, Ui.accent);
                browse.setOnClickListener(v -> {
                    sheet.dismiss();
                    this.startActivity(new Intent((Context)this, NativeWorkspaceActivity.class).putExtra("engine", this.engine).putExtra("cwd", this.cwd));
                });
                list.addView(browse);
            }, 0.68);
        });
    }

    private TextView projectSheetSection(String label) {
        TextView view = Ui.sectionLabel((Context)this, label);
        view.setPadding(Ui.dp((Context)this, 8), Ui.dp((Context)this, 10), 0, Ui.dp((Context)this, 4));
        return view;
    }

    private View projectSheetRow(String name, String path, boolean active, Runnable click) {
        LinearLayout row = Ui.row((Context)this);
        row.setGravity(16);
        row.setBackground(Ui.round(active ? Ui.accentSoft : 0, Ui.dp((Context)this, 12)));
        row.setPadding(Ui.dp((Context)this, 12), Ui.dp((Context)this, 10), Ui.dp((Context)this, 12), Ui.dp((Context)this, 10));
        ImageView icon = new ImageView((Context)this);
        icon.setImageResource(R.drawable.ic_folder);
        icon.setColorFilter(active ? Ui.accent : Ui.muted);
        row.addView((View)icon, Ui.w(Ui.dp((Context)this, 17), Ui.dp((Context)this, 17)));
        LinearLayout labels = Ui.col((Context)this);
        LinearLayout.LayoutParams labelSpace = new LinearLayout.LayoutParams(0, -2, 1.0f);
        labelSpace.leftMargin = Ui.dp((Context)this, 10);
        row.addView((View)labels, labelSpace);
        labels.addView(Ui.text((Context)this, name, 14.0f, active ? Ui.onAccentSoft : Ui.text, Ui.medium((Context)this)));
        TextView meta = Ui.text((Context)this, path, 11.5f, active ? Ui.onAccentSoft : Ui.faint, null);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams metaSpace = Ui.fill();
        metaSpace.topMargin = Ui.dp((Context)this, 2);
        labels.addView((View)meta, metaSpace);
        if (active) {
            ImageView check = new ImageView((Context)this);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(Ui.accent);
            row.addView((View)check, Ui.w(Ui.dp((Context)this, 18), Ui.dp((Context)this, 18)));
        }
        Ui.touchable(row);
        if (!active) {
            Ui.rippleRounded(row, 12, Ui.text);
        }
        row.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams space = Ui.fill();
        space.bottomMargin = Ui.dp((Context)this, 2);
        row.setLayoutParams(space);
        return row;
    }

    private void selectProject(String path, boolean resetThread) {
        if (path == null || path.isEmpty()) {
            return;
        }
        if (!this.turnId.isEmpty()) {
            NativeUi.toast((Activity)this, "\u8bf7\u5148\u7ed3\u675f\u5f53\u524d\u4efb\u52a1");
            return;
        }
        boolean changed = !path.equals(this.cwd);
        this.cwd = path;
        if (this.projectPill != null) {
            TextView label = (TextView)this.projectPill.getChildAt(this.projectPill.getChildCount() - 1);
            label.setText(NativeHomeActivity.folder(this.cwd));
        }
        if (changed || resetThread) {
            this.thread = null;
            this.resumed = false;
            this.usage = null;
            this.threadId = resetThread ? "" : this.getSharedPreferences("native-chat", 0).getString("conversation-" + this.cwd.hashCode(), "");
            this.renderMessages(false);
            if (this.threadId.isEmpty()) {
                this.refreshUsage();
                this.updateUsageRow();
            } else {
                this.loadThread(this.threadId);
            }
        }
        this.persist();
        this.updateSubtitle();
        this.refreshDrawerData();
        this.renderDrawer();
        if (changed) {
            NativeUi.toast((Activity)this, "\u5de5\u4f5c\u76ee\u5f55 \u00b7 " + NativeHomeActivity.folder(this.cwd));
        }
    }

    private boolean composerContent() {
        return this.composer!=null&&(!this.composer.getText().toString().trim().isEmpty()||!this.attachments.isEmpty()
                ||this.quote!=null&&!this.quote.optString("text").trim().isEmpty());
    }

    private String executionState() {
        JSONObject control=this.thread==null?null:this.thread.optJSONObject("executionControl");
        return control==null?"":control.optString("status");
    }
    /** Only a task that is actually running needs a follow-up queue. */
    private boolean executionBusy() {
        return !this.turnId.isEmpty() || this.collaboration != null && this.collaboration.working();
    }

    /** A stopped task does not need a continue click when ordinary queue work is ready. */
    private boolean autoQueuePending() {
        return ExecutionButton.autoQueuePending(this.thread);
    }

    private boolean pausedQueueWaiting() {
        if(this.thread==null||!this.thread.optBoolean("messageQueuePaused"))return false;
        JSONArray queue=this.thread.optJSONArray("messageQueue");
        for(int i=0;queue!=null&&i<queue.length();i++){
            JSONObject entry=queue.optJSONObject(i);
            if(entry!=null&&!entry.optBoolean("executionResume")&&entry.optString("status").equals("queued"))return true;
        }
        return false;
    }

    private ExecutionButton.Action executionAction() {
        boolean active=!this.turnId.isEmpty()||this.collaboration!=null&&this.collaboration.working();
        if(!this.composerContent()&&this.autoQueuePending())return ExecutionButton.Action.WAIT;
        return ExecutionButton.action(this.composerContent(),active,this.executionState());
    }

    private void updateExecutionButton() {
        if(this.sendButton==null)return;
        ExecutionButton.Action action=this.executionAction();
        int icon=action==ExecutionButton.Action.STOP?R.drawable.ic_stop:
                action==ExecutionButton.Action.SEND?R.drawable.ic_send:R.drawable.ic_play;
        ((ImageView)this.sendButton.getChildAt(0)).setImageResource(icon);
        String description=action==ExecutionButton.Action.STOP?"停止当前执行":action==ExecutionButton.Action.RESUME?"继续之前的任务":
                action==ExecutionButton.Action.WAIT?(this.autoQueuePending()?"待发消息自动接力":this.executionState().equals("stopping")||this.executionState().equals("pausing")?"正在停止":"等待继续原任务"):
                action==ExecutionButton.Action.SEND?(this.executionBusy()?"加入待发队列":"发送任务"):"执行任务";
        this.sendButton.setContentDescription(description);
        boolean enabled=!this.submitting&&action!=ExecutionButton.Action.WAIT&&action!=ExecutionButton.Action.IDLE;
        this.sendButton.setEnabled(enabled);this.sendButton.setAlpha(enabled?1f:0.5f);
    }

    private void executionClick() {
        if(this.submitting)return;
        ExecutionButton.Action action=this.executionAction();
        if(action==ExecutionButton.Action.SEND){this.sendMessage();return;}
        if(action!=ExecutionButton.Action.STOP&&action!=ExecutionButton.Action.RESUME)return;
        if(!NativeEngine.ready()){this.startService();return;}
        String owner=this.threadId;this.submitting=true;this.updateControls();
        NativeEngine.call("native/chat/execution/"+(action==ExecutionButton.Action.STOP?"interrupt":"resume"),
                NativeUi.json("threadId",owner,"turnId",this.turnId),r->{
            this.submitting=false;
            if(!owner.equals(this.threadId)){this.updateControls();return;}
            this.applyThreadView(r.optJSONObject("thread"));this.persist();this.updateControls();
            if(this.messageQueue!=null)this.messageQueue.refresh();
        },error->{this.submitting=false;this.updateControls();if(owner.equals(this.threadId))NativeUi.error(this,error);});
    }

    private void sendMessage() {
        if (this.submitting) {
            return;
        }
        String draft = this.composer.getText().toString().trim();
        String text = draft;
        if (this.quote != null) {
            String quoted = this.quote.optString("text", "").trim();
            String sender = this.quote.optString("sender", "").trim();
            if (!quoted.isEmpty()) {
                text = "> " + (sender.isEmpty() ? "\u5f15\u7528" : "\u5f15\u7528 " + sender) + "\uff1a\n> "
                        + quoted.replace("\r\n", "\n").replace("\n", "\n> ") + "\n\n" + draft;
            }
        }
        if (text.isEmpty() && this.attachments.isEmpty()) {
            this.executionClick();
            return;
        }
        if (!NativeEngine.ready()) {
            this.startService();
            return;
        }
        if (!this.pendingThread.isEmpty() && this.thread == null) {
            NativeUi.toast(this, "正在读取会话历史，请稍候");
            return;
        }
        ++this.historyGeneration;
        this.pendingThread = "";
        this.submitting = true;
        this.followLatest = true;
        this.updateControls();
        JSONArray input = new JSONArray();
        if (!text.isEmpty()) {
            input.put(NativeUi.json("type", "text", "text", text));
        }
        for (JSONObject image : this.attachments) {
            input.put(image);
        }
        String originalThread = this.threadId;
        int originalEngineGeneration = this.engineGeneration;
        Runnable submit = () -> {
            String owner = this.threadId;
            if(this.collaboration.enabled()){
                Consumer<JSONObject> accepted = r -> {
                    this.submitting=false;
                    if(!owner.equals(this.threadId)){this.updateControls();return;}
                    this.composer.setText("");this.attachments.clear();this.quote=null;this.refreshQuote();this.updateAttach();
                    this.applyThreadView(r.optJSONObject("thread"));this.persist();this.updateControls();this.refreshDrawerData();
                };
                Consumer<String> rejected = message -> {
                    this.submitting=false;this.updateControls();
                    if(owner.equals(this.threadId))NativeUi.error(this,message);
                };
                if(this.executionBusy()) this.collaboration.submit(input,accepted,rejected);
                else this.collaboration.submitNow(input,accepted,rejected);
                return;
            }
            this.normalizeEffort();
            String fingerprint=this.threadId+"independent"+input.toString();
            android.content.SharedPreferences requests=getSharedPreferences("message-queue-requests",0);
            String request=requests.getString("fingerprint","").equals(fingerprint)?requests.getString("request",UUID.randomUUID().toString()):UUID.randomUUID().toString();
            requests.edit().putString("fingerprint",fingerprint).putString("request",request).apply();
            boolean queued = this.executionBusy();
            JSONObject args = NativeUi.json("threadId", this.threadId, "input", input, "model", this.model, "effort", this.effort, "clientRequestId", request);
            if (queued) try { args.put("mode", "independent"); } catch (JSONException ignored) {}
            else try { args.put("engine", this.engine); } catch (JSONException ignored) {}
            this.call(queued ? "native/chat/queue/submit" : "native/chat/turn/start", args, r -> {
            this.submitting = false;
            requests.edit().remove("fingerprint").remove("request").apply();
            if(!owner.equals(this.threadId)){this.updateControls();this.refreshDrawerData();return;}
            if (r.optBoolean("queued")) NativeUi.toast(this, r.optString("message", queued ? "任务已排队" : "等待执行名额，稍后开始"));
            if (r.optBoolean("handoffClipped")) NativeUi.toast(this, "较长历史已节选交接，完整记录保留供 Agent 读取");
            this.composer.setText("");
            this.attachments.clear();
            this.quote = null;
            this.refreshQuote();
            this.updateAttach();
            JSONObject responseThread = r.optJSONObject("thread");
            if (responseThread != null) this.applyThreadView(responseThread);
            JSONObject turn = r.optJSONObject("turn");
            /* The response thread already contains the started turn. Merging it again rebuilt
               the same live turn twice, which showed up as a flash when execution began. */
            if (turn != null && this.currentTurn(turn.optString("id")) == null) {
                turn = ConversationContent.startedTurn(null, turn);
                this.putTurn(turn);
                this.turnId = "inProgress".equals(turn.optString("status")) ? turn.optString("id") : "";
                this.renderMessages(false);
            }
            this.persist();
        });
        };
        if (this.threadId.isEmpty()) {
            this.call("thread/start", NativeUi.json("cwd", this.cwd, "model", this.model), r -> {
                if(!originalThread.equals(this.threadId)||originalEngineGeneration!=this.engineGeneration){this.submitting=false;this.updateControls();return;}
                this.thread = r.optJSONObject("thread");
                this.threadId = this.thread.optString("id");
                this.resumed = true;
                this.persist();
                submit.run();
            });
        } else if (!this.resumed) {
            this.call("thread/resume", NativeUi.json("threadId", this.threadId), r -> {
                if(!originalThread.equals(this.threadId)||originalEngineGeneration!=this.engineGeneration){this.submitting=false;this.updateControls();return;}
                this.resumed = true;
                submit.run();
            });
        } else {
            submit.run();
        }
    }

    private String stoppingTurn = "";
    private void interrupt() {
        if(this.turnId.isEmpty()&&this.collaboration.working()){this.collaboration.stop();return;}
        if (this.turnId.isEmpty() || this.turnId.equals(this.stoppingTurn)) {
            return;
        }
        String owner = this.threadId, target = this.turnId;
        this.stoppingTurn = target;
        this.updateStatusLine();
        NativeEngine.call("native/chat/turn/interrupt", NativeUi.json("engine", this.engine, "threadId", owner, "turnId", target), r -> {
            AskQueue.cancelOrdinary();
            this.askHost.removeAllViews();
            this.pollStopped(owner, target, 0);
        }, error -> {
            this.stoppingTurn = "";
            this.updateStatusLine();
            NativeUi.error(this, error);
        });
    }

    private void pollStopped(String owner, String target, int attempt) {
        if (!owner.equals(this.threadId) || !target.equals(this.stoppingTurn) || this.isFinishing()) return;
        NativeEngine.call("native/chat/turn/status", NativeUi.json("threadId", owner, "engine", this.engine), result -> {
            if (!owner.equals(this.threadId) || !target.equals(this.stoppingTurn)) return;
            JSONObject value = result.optJSONObject("thread");
            JSONArray turns = value == null ? null : value.optJSONArray("turns");
            boolean running = false;
            for (int i = 0; turns != null && i < turns.length(); i++) {
                JSONObject turn = turns.optJSONObject(i);
                if (turn != null && target.equals(turn.optString("id"))) running = NativeHomeActivity.turnActive(turn.optString("status"));
            }
            if (!running) {
                this.stoppingTurn = "";
                this.submitting = false;
                this.stopTurnClock();
                this.viewCache.forget(owner);
                this.applyThreadView(value);
                this.persist();
                this.refreshUsage();
            } else if (attempt < 15) this.handler.postDelayed(() -> this.pollStopped(owner, target, attempt + 1), 500);
            else {
                this.stoppingTurn = "";
                NativeUi.error(this, "停止尚未完成，可以再次点击停止重试");
            }
        }, error -> {
            this.stoppingTurn = "";
            NativeUi.error(this, error);
        });
    }

    private void putTurn(JSONObject value) {
        JSONArray turns;
        if (this.thread == null) {
            this.thread = NativeUi.json("id", this.threadId, "title", "\u65b0\u5bf9\u8bdd", "turns", new JSONArray());
            if (this.thread == null) {
                return;
            }
        }
        if ((turns = this.thread.optJSONArray("turns")) == null) {
            turns = new JSONArray();
            try {
                this.thread.put("turns", turns);
            }
            catch (JSONException jSONException) {
                // empty catch block
            }
        }
        for (int i = 0; i < turns.length(); ++i) {
            if (!turns.optJSONObject(i).optString("id").equals(value.optString("id"))) continue;
            try {
                turns.put(i, value);
            }
            catch (JSONException jSONException) {
                // empty catch block
            }
            return;
        }
        turns.put(value);
    }

    private JSONObject currentTurn(String id2) {
        if (this.thread == null) {
            return null;
        }
        JSONArray turns = this.thread.optJSONArray("turns");
        if (turns == null) {
            return null;
        }
        for (int i = 0; i < turns.length(); ++i) {
            JSONObject t = turns.optJSONObject(i);
            if (!t.optString("id").equals(id2)) continue;
            return t;
        }
        return null;
    }

    private void item(JSONObject turn, JSONObject value) {
        if (turn == null || value == null) {
            return;
        }
        JSONArray items = turn.optJSONArray("items");
        if (items == null) {
            items = new JSONArray();
            try {
                turn.put("items", items);
            }
            catch (JSONException jSONException) {
                // empty catch block
            }
        }
        if ("userMessage".equals(value.optString("type")) && !value.optBoolean("aixsInput")) {
            for (int i = items.length() - 1; i >= 0; --i) {
                if (items.optJSONObject(i).optBoolean("aixsInput")) items.remove(i);
            }
        }
        for (int i = 0; i < items.length(); ++i) {
            if (!items.optJSONObject(i).optString("id").equals(value.optString("id"))) continue;
            try {
                items.put(i, value);
            }
            catch (JSONException jSONException) {
                // empty catch block
            }
            return;
        }
        items.put(value);
    }

    @Override
    public void event(JSONObject event) {
        if (event.has("workflowId")) {
            if(!event.optString("conversationId").isEmpty()&&!this.threadId.equals(event.optString("conversationId")))return;
            if(this.collaboration.stream(event))this.repaint.request();
            if(this.collaboration.owns(event.optString("workflowId"))&&event.optString("type").equals("approval"))this.showApproval(event);
            String method=event.optString("method");
            if(event.optLong("attemptSequence")<=0||method.equals("turn/started")||method.equals("turn/completed")
                ||method.equals("serverRequest/resolved")||event.optString("type").equals("approval")
                ||CollaborationEvents.turn(this.thread,event.optString("workflowId"))==null)this.collaborationRefresh.request();
            return;
        }
        if(event.optString("method").equals("collaboration/changed")){
            JSONObject params=event.optJSONObject("params");
            if(params!=null&&this.threadId.equals(params.optString("conversationId")))this.collaborationRefresh.request();return;
        }
        if(event.optString("method").equals("native/queueChanged")){
            JSONObject params=event.optJSONObject("params");
            if(params!=null&&this.threadId.equals(params.optString("threadId")))this.queueRefresh.request();return;
        }
        block29: {
            String type = event.optString("type");
            if ("native/threadsChanged".equals(event.optString("method"))) {
                if (this.drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)) this.refreshDrawerData();
                return;
            }
            if (type.equals("native/ready")) {
                this.refresh();
                return;
            }
            if (type.equals("native/stopped")) {
                this.status("Agent \u670d\u52a1\u5df2\u505c\u6b62", Ui.warn, true);
                this.updateControls();
                return;
            }
            if (type.equals("native/cleanup")) {
                int purged = event.optInt("purged", 0);
                int failed = event.optInt("failed", 0);
                if (purged > 0) NativeUi.toast((Activity)this, "\u5df2\u6e05\u7406 " + purged + " \u4e2a\u5df2\u5220\u9664\u4f1a\u8bdd\u7684\u6b8b\u7559\u6570\u636e");
                if (failed > 0) NativeUi.toast((Activity)this, "\u6709 " + failed + " \u4e2a\u5df2\u5220\u9664\u4f1a\u8bdd\u5f85\u4e0b\u6b21\u542f\u52a8\u91cd\u8bd5");
                this.refreshDrawerData();
                return;
            }
            if (type.equals("approval")) {
                this.showApproval(event);
                return;
            }
            JSONObject p = event.optJSONObject("params");
            if (p == null || !this.threadId.equals(p.optString("threadId"))) {
                return;
            }
            String method = event.optString("method");
            try {
                if (method.equals("serverRequest/resolved")) {
                    String resolved = p.optString("requestId");
                    this.approvals.remove(resolved);
                    for (int i = this.askHost.getChildCount() - 1; i >= 0; i--) {
                        Object tag = this.askHost.getChildAt(i).getTag();
                        if (tag instanceof String && ((String)tag).startsWith(resolved + "-question-")) this.askHost.removeViewAt(i);
                    }
                }
                if (p.optJSONObject("conversationUsage") != null) {
                    this.usage = p.optJSONObject("conversationUsage");
                    if (this.thread != null) this.thread.put("usage", this.usage);
                    this.updateUsageRow();
                }
                JSONObject t;
                if (p.has("progress")) {
                    JSONObject active = this.currentTurn(p.optString("turnId",
                        p.optJSONObject("turn") == null ? "" : p.optJSONObject("turn").optString("id")));
                    if (active != null) {
                        active.put("progress", p.optJSONObject("progress"));
                        JSONArray activity = active.optJSONArray("activity");
                        if (activity == null) { activity = new JSONArray(); active.put("activity", activity); }
                        JSONObject progress = p.optJSONObject("progress");
                        JSONObject last = activity.optJSONObject(activity.length() - 1);
                        if (last == null || !last.optString("phase").equals(progress.optString("phase"))
                            || !last.optString("detail").equals(progress.optString("detail"))
                            || last.optInt("attempt") != progress.optInt("attempt")) {
                            activity.put(progress);
                            if (activity.length() > 30) activity.remove(0);
                        }
                    }
                    this.updateStatusLine();
                }
                if (method.equals("turn/started")) {
                    JSONObject t2 = p.getJSONObject("turn");
                    if (p.has("progress")) t2.put("progress", p.optJSONObject("progress"));
                    this.putTurn(t2);
                    this.turnId = t2.getString("id");
                    this.turnStartedAt = SystemClock.elapsedRealtime();
                    this.startTurnClock();
                    break block29;
                }
                if (method.equals("turn/completed")) {
                    String finished = this.turnId;
                    JSONObject completed = p.optJSONObject("turn");
                    if (completed != null) this.putTurn(completed);
                    this.stoppingTurn = "";
                    long elapsed = this.turnStartedAt > 0L ? SystemClock.elapsedRealtime() - this.turnStartedAt : 0L;
                    this.rememberTurn(finished, elapsed, 0);
                    this.turnId = "";
                    this.submitting = false;
                    this.stopTurnClock();
                    this.viewCache.forget(this.threadId);
                    this.renderMessages(false);
                    this.loadThread(this.threadId);
                    this.refreshUsage(finished);
                    if (this.collaborationAfterTurn) { this.collaborationAfterTurn=false;this.handler.postDelayed(this::openCollaboration, 500); }
                    return;
                }
                JSONObject active = this.currentTurn(p.optString("turnId", this.turnId));
                if (active == null && (p.isNull("turnId") || !p.has("turnId"))) active = this.currentTurn(this.turnId);
                if (method.equals("item/started") || method.equals("item/completed")) {
                    JSONObject raw = p.optJSONObject("item");
                    if (raw != null && "userMessage".equals(raw.optString("type"))) {
                        this.item(active, raw);
                    } else RealtimeEvents.apply(active, method, p);
                } else RealtimeEvents.apply(active, method, p);
            }
            catch (JSONException jSONException) {
                // empty catch block
            }
        }
        this.repaint.request();
    }

    private final CoalescedUpdate collaborationRefresh = new CoalescedUpdate(
        () -> { if(this.collaboration!=null)this.collaboration.refresh(); },
        task -> this.handler.postDelayed(task, 250L), task -> this.handler.removeCallbacks(task));
    private final CoalescedUpdate queueRefresh = new CoalescedUpdate(
        () -> {if(this.messageQueue!=null)this.messageQueue.refresh();},
        task -> this.handler.postDelayed(task,150L),task -> this.handler.removeCallbacks(task));

    /** Load older collaboration entries; keep the viewport anchored while prepending. */
    private void loadEarlierCollaboration(JSONObject turn) {
        if (loadingCollaborationHistory || turn == null || this.chat == null || this.messages == null) return;
        String workflow = turn.optString("workflowId");
        if (workflow.isEmpty()) return;
        JSONArray before = turn.optJSONArray("historyBefore");
        if (before == null || before.length() != 2) return;
        loadingCollaborationHistory = true;
        historyAnchorScroll = this.chat.getScrollY();
        historyAnchorHeight = this.messages.getHeight();
        this.followLatest = false;
        JSONObject args = NativeUi.json("workflowId", workflow, "before", before, "limit", 40);
        NativeEngine.call("native/collaboration/conversation", args, r -> {
            loadingCollaborationHistory = false;
            if (isFinishing() || isDestroyed() || this.thread == null) return;
            JSONArray entries = r.optJSONArray("entries");
            JSONObject cached = collaborationHistory.get(workflow);
            if (cached == null) cached = new JSONObject();
            JSONArray all = cached.optJSONArray("entries");
            if (all == null) all = new JSONArray();
            for (int i = 0; entries != null && i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("id");
                boolean duplicate = false;
                for (int j = 0; j < all.length(); j++) {
                    if (id.equals(all.optJSONObject(j) == null ? "" : all.optJSONObject(j).optString("id"))) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) all.put(entry);
            }
            ArrayList<JSONObject> sorted = new ArrayList<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.optJSONObject(i);
                if (item != null) sorted.add(item);
            }
            sorted.sort((left, right) -> {
                int at = Double.compare(left.optDouble("at", 0), right.optDouble("at", 0));
                return at != 0 ? at : left.optString("id").compareTo(right.optString("id"));
            });
            JSONArray ordered = new JSONArray();
            for (JSONObject item : sorted) ordered.put(item);
            try {
                cached.put("entries", ordered);
                cached.put("hasMore", r.optBoolean("hasMore"));
                cached.put("before", r.optJSONArray("before"));
                cached.put("partial", r.optBoolean("historyPartial"));
                cached.put("omitted", r.optInt("omittedItems"));
            } catch (Exception ignored) {}
            collaborationHistory.put(workflow, cached);
            mergeCachedCollaboration(this.thread);
            this.renderMessages(false);
        }, e -> {
            loadingCollaborationHistory = false;
            historyAnchorScroll = -1;
            NativeUi.error(this, e);
        });
    }

    private void mergeCachedCollaboration(JSONObject thread) {
        if (thread == null) return;
        JSONArray turns = thread.optJSONArray("turns");
        for (int i = 0; turns != null && i < turns.length(); i++) {
            JSONObject turn = turns.optJSONObject(i);
            if (turn == null) continue;
            String workflow = turn.optString("workflowId");
            JSONObject cached = collaborationHistory.get(workflow);
            if (cached == null) continue;
            mergeHistoryEntries(turn, cached.optJSONArray("entries"));
            try {
                if (cached.has("hasMore")) turn.put("historyHasMore", cached.optBoolean("hasMore"));
                if (cached.has("before")) turn.put("historyBefore", cached.opt("before"));
                if (cached.has("partial")) turn.put("historyPartial", cached.optBoolean("partial"));
                int omitted = cached.optInt("omitted", 0);
                if (omitted > turn.optInt("historyOmittedItems", 0)) turn.put("historyOmittedItems", omitted);
            } catch (Exception ignored) {}
        }
    }

    private void mergeHistoryEntries(JSONObject turn, JSONArray entries) {
        if (entries == null || entries.length() == 0) return;
        JSONArray existing = turn.optJSONArray("items");
        if (existing == null) existing = new JSONArray();
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < existing.length(); i++) {
            JSONObject item = existing.optJSONObject(i);
            if (item != null) ids.add(item.optString("id"));
        }
        ArrayList<JSONObject> add = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("id");
            if (id.isEmpty() || ids.contains(id)) continue;
            JSONObject item = historyItem(entry);
            if (item == null) continue;
            ids.add(id);
            add.add(item);
        }
        if (add.isEmpty()) return;
        add.sort((left, right) -> {
            int at = Double.compare(left.optDouble("at", 0), right.optDouble("at", 0));
            return at != 0 ? at : left.optString("id").compareTo(right.optString("id"));
        });
        JSONArray merged = new JSONArray();
        for (JSONObject item : add) merged.put(item);
        for (int i = 0; i < existing.length(); i++) merged.put(existing.opt(i));
        try { turn.put("items", merged); } catch (Exception ignored) {}
    }

    private JSONObject historyItem(JSONObject entry) {
        String kind = entry.optString("kind");
        String id = entry.optString("id");
        try {
            JSONObject item;
            if (kind.equals("user")) {
                item = new JSONObject().put("type", "userMessage").put("content",
                    new JSONArray().put(new JSONObject().put("type", "text").put("text", entry.optString("text"))));
            } else if (kind.equals("error")) {
                item = new JSONObject().put("type", "agentMessage").put("text", entry.optString("text"))
                    .put("collaborationError", true);
            } else {
                item = entry.optJSONObject("item");
                if (item == null) return null;
                item = new JSONObject(item.toString());
            }
            item.put("id", id);
            item.put("participant", entry.optString("participant"));
            item.put("participantId", entry.optString("participantId"));
            item.put("engine", entry.optString("engine"));
            item.put("model", entry.optString("model"));
            item.put("taskId", entry.optString("taskId"));
            item.put("taskTitle", entry.optString("taskTitle"));
            item.put("attemptId", entry.optString("attemptId"));
            item.put("attemptNumber", entry.optInt("attemptNumber", 1));
            item.put("attemptStatus", entry.optString("attemptStatus"));
            item.put("at", entry.optDouble("at", 0));
            return item;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void renderMessages(boolean animate) {
        if (this.messages == null) {
            return;
        }
        int previousScroll = this.chat.getScrollY();
        boolean scrollToLatest = this.followLatest;
        boolean structure = false;
        this.turnId = "";
        String heading = "\u65b0\u5bf9\u8bdd";
        boolean streaming = false;
        boolean suppress = android.os.Build.VERSION.SDK_INT >= 29;
        if (suppress) this.messages.suppressLayout(true);
        this.currentInsight = SessionInsight.of(this.thread);
        if (this.thread == null) {
            if (this.messages.getChildCount() > 0) {
                structure = true;
            }
            this.messages.removeAllViews();
            this.turnHosts.clear();
            this.turnSigs.clear();
            this.turnRunning.clear();
            if (this.threadId.isEmpty()) this.emptyState();
            else {
                heading = this.failedThread.equals(this.threadId) ? "会话读取失败" : "正在读取会话";
                TextView pending = Ui.text(this, heading + "…", 14, Ui.muted, null);
                Ui.pad(pending, 20, 24, 20, 24);
                this.messages.addView(pending, Ui.fill());
            }
            this.emptyRendered = true;
            this.updateInsightEntry();
            if (this.insightPanel != null) this.insightPanel.update(null);
        } else {
            int count;
            if (this.emptyRendered) {
                this.messages.removeAllViews();
                this.turnHosts.clear();
                this.turnSigs.clear();
                this.turnRunning.clear();
                this.emptyRendered = false;
                structure = true;
            }
            heading = ConversationContent.title(this.thread);
            JSONArray turns = this.thread.optJSONArray("turns");
            int n = count = turns == null ? 0 : turns.length();
            while (this.turnHosts.size() > count) {
                this.messages.removeView(this.turnHosts.remove(this.turnHosts.size() - 1));
                this.turnSigs.remove(this.turnSigs.size() - 1);
                if (!this.turnRunning.isEmpty()) this.turnRunning.remove(this.turnRunning.size() - 1);
                structure = true;
            }
            for (int i = 0; i < count; ++i) {
                LinearLayout host;
                JSONObject turn = turns.optJSONObject(i);
                if (turn == null) continue;
                boolean running = NativeHomeActivity.turnActive(ConversationContent.string(turn, "status", ""));
                if (running) {
                    this.turnId = ConversationContent.string(turn, "id", "");
                    if (this.turnStartedAt == 0L) {
                        this.turnStartedAt = SystemClock.elapsedRealtime();
                        this.handler.removeCallbacks(this.ticker);
                        this.handler.post(this.ticker);
                    }
                }
                boolean settled = i < this.turnRunning.size() && !this.turnRunning.get(i);
                String signature = RealtimeEvents.renderSignature(turn,running,settled,
                        i < this.turnSigs.size() ? this.turnSigs.get(i) : null);
                if (i < this.turnRunning.size()) this.turnRunning.set(i, running);
                else this.turnRunning.add(running);
                if (i < this.turnHosts.size()) {
                    host = this.turnHosts.get(i);
                    if (signature.equals(this.turnSigs.get(i))) {
                        if (!running) continue;
                        streaming = true;
                        continue;
                    }
                    host.removeAllViews();
                    this.turnSigs.set(i, signature);
                } else {
                    host = Ui.col((Context)this);
                    this.messages.addView((View)host, Ui.fill());
                    this.turnHosts.add(host);
                    this.turnSigs.add(signature);
                    structure = true;
                }
                if (!this.renderTurn(host, turn, running)) continue;
                streaming = true;
            }
        }
        if (suppress) this.messages.suppressLayout(false);
        this.topTitle.setText(heading.length() > 42 ? heading.substring(0, 42) + "\u2026" : heading);
        this.updateSubtitle();
        this.updateControls();
        this.updateInsightEntry();
        if (this.insightPanel != null && this.thread != null) this.insightPanel.update(this.thread, this.currentInsight);
        if (animate && structure) {
            for (int i = Math.max(0, this.messages.getChildCount() - 3); i < this.messages.getChildCount(); ++i) {
                Ui.enter(this.messages.getChildAt(i), i);
            }
        }
        this.chat.post(() -> {
            if (historyAnchorScroll >= 0 && this.messages.getHeight() > 0) {
                int delta = this.messages.getHeight() - this.historyAnchorHeight;
                this.chat.scrollTo(0, Math.max(0, this.historyAnchorScroll + delta));
                historyAnchorScroll = -1;
                historyAnchorHeight = 0;
            } else if (this.thread == null) {
                this.chat.scrollTo(0, 0);
            } else if (scrollToLatest) {
                /* 直接贴底：按高度差滚动会在重建、行高抖动时把视口甩到中途。 */
                this.chat.scrollTo(0, Math.max(0, this.messages.getHeight() - this.chat.getHeight() + this.chat.getPaddingBottom()));
            } else {
                this.chat.scrollTo(0, previousScroll);
            }
            this.followLatest = scrollToLatest;
            this.hintScrollButton();
        });
        if (!streaming) {
            this.statusIdle();
        }
    }

    private static String reasoningText(JSONObject entry) {
        StringBuilder builder = new StringBuilder();
        JSONArray summary = entry.optJSONArray("summary");
        for (int i = 0; summary != null && i < summary.length(); i++) {
            JSONObject part = summary.optJSONObject(i);
            builder.append(part == null ? summary.optString(i) : part.optString("text"));
        }
        if (builder.length() == 0) {
            JSONArray content = entry.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject part = content.optJSONObject(i);
                builder.append(part == null ? content.optString(i) : part.optString("text"));
            }
        }
        return builder.toString();
    }

    private static boolean turnActive(String status) {
        return "inProgress".equals(status) || "starting".equals(status) || "running".equals(status) || "executing".equals(status);
    }

    private boolean renderTurn(LinearLayout host, JSONObject turn, boolean running) {
        if(turn.has("workflowId"))return this.renderCollaborationTurn(host,turn);
        this.renderingEngine = ConversationContent.string(turn, "engine", this.engine);
        String error;
        boolean live = false;
        JSONArray items = turn.optJSONArray("items");
        // Keep tool history available and display even an empty activity at its start.
        int summaryIndex = -1;
        if (!running && items != null) {
            for (int j = items.length() - 1; j >= 0; --j) {
                JSONObject candidate = items.optJSONObject(j);
                if (candidate == null || !"agentMessage".equals(ConversationContent.string(candidate, "type", ""))) continue;
                if (ConversationContent.item(candidate).isEmpty()) continue;
                summaryIndex = j;
                break;
            }
        }
        if (items != null) {
            for (int j = 0; j < items.length(); ++j) {
                boolean tail;
                JSONObject entry = items.optJSONObject(j);
                if (entry == null) continue;
                String childKey = SessionInsight.childKey(entry, this.renderingEngine);
                if (childKey != null) {
                    ArrayList<JSONObject> group = new ArrayList<JSONObject>();
                    group.add(entry);
                    int end = j + 1;
                    while (end < items.length()) {
                        JSONObject next = items.optJSONObject(end);
                        if (next == null || !childKey.equals(SessionInsight.childKey(next, this.renderingEngine))) break;
                        group.add(next);
                        ++end;
                    }
                    live |= this.renderChildGroup(host, turn, childKey, group, running);
                    j = end - 1;
                    continue;
                }
                String type = ConversationContent.string(entry, "type", "");
                String extracted = ConversationContent.item(entry);
                if (extracted.isEmpty() && type.equals("reasoning")) extracted = NativeHomeActivity.reasoningText(entry);
                final String text = extracted;
                if (text.isEmpty() && (type.equals("userMessage") || type.equals("agentMessage"))) continue;
                boolean bl = tail = running && j == items.length() - 1;
                if (type.equals("userMessage")) {
                    String sourceThread = this.threadId;
                    this.transcript().user(host, text, () -> this.editMessage(sourceThread, turn, entry), () -> this.quoteMessage(text, "\u6211"));
                    continue;
                }
                if (type.equals("agentMessage")) {
                    if (!running && j != summaryIndex) continue;
                    this.transcript().assistant(host, text, tail, tail ? "" : this.turnFooter(ConversationContent.string(turn, "id", "")), () -> this.quoteMessage(text, NativeUi.engineName(this.engine)));
                    live |= tail;
                    continue;
                }
                String label = RealtimeEvents.label(entry, running);
                String id2 = ConversationContent.string(turn, "id", "") + ":" + ConversationContent.string(entry, "id", String.valueOf(j));
                if (type.equals("reasoning") && running && this.autoExpandedActivities.add(id2)) {
                    this.expandedActivities.add(id2);
                }
                if (type.equals("fileReport")) {
                    JSONObject counts = entry.optJSONObject("counts");
                    int total = counts == null ? 0 : counts.optInt("created", 0) + counts.optInt("modified", 0) + counts.optInt("deleted", 0);
                    if (total == 0) continue;
                }
                this.transcript().activity(host, id2, label, type, text, this.expandedActivities, RealtimeEvents.preview(entry), entry);
                live |= tail;
            }
        }
        if ("interrupted".equals(turn.optString("status"))) {
            TextView stopped = Ui.text(this, "已停止", 11.5f, Ui.muted, null);
            Ui.pad(stopped, 0, 4, 0, 8);
            host.addView(stopped, Ui.fill());
        } else if (!(error = ConversationContent.error(turn)).isEmpty()) {
            this.errorLine(host, error);
        }
        return live || running;
    }

    private static boolean collaborationFinished(String status) {
        return status.equals("completed") || status.equals("cancelled") || status.equals("failed")
            || status.equals("interrupted") || status.equals("awaiting_acceptance")
            || status.equals("partial_failure") || status.equals("needs_reconciliation")
            || status.equals("succeeded");
    }

    private boolean renderCollaborationTurn(LinearLayout host,JSONObject turn){
        JSONArray items=turn.optJSONArray("items");boolean live=false;JSONArray reports=new JSONArray();
        if (turn.optBoolean("historyHasMore")) {
            ConversationRenderer earlier = new ConversationRenderer(this, "");
            host.addView(earlier.ghost(loadingCollaborationHistory ? "正在加载更早记录…" : "加载更早记录",
                R.drawable.ic_history, () -> loadEarlierCollaboration(turn)));
        }
        for(int i=0;items!=null&&i<items.length();i++){
            JSONObject item=items.optJSONObject(i);if(item==null)continue;
            String type=item.optString("type");String rawText=ConversationContent.item(item);String itemId=item.optString("id");
            if(rawText.isEmpty()&&type.equals("reasoning"))rawText=NativeHomeActivity.reasoningText(item);
            final String text=rawText;
            if(type.equals("userMessage")){
                new ConversationRenderer(this,"").user(host,text,()->{
                    this.composer.setText(ConversationContent.editableText(item));this.composer.requestFocus();
                    NativeUi.toast(this,"内容已带回输入框，修改后发送，原记录保留");
                },()->this.quoteMessage(text,"\u6211"));continue;
            }
            String source=NativeUi.engineName(item.optString("engine"))+" · "+item.optString("model");
            ConversationRenderer renderer=new ConversationRenderer(this,source);
            boolean active=Arrays.asList("starting","running","waiting_input","stopping").contains(item.optString("attemptStatus"));
            boolean generating=active&&RealtimeEvents.active(item.optString("status"));
            if(item.optBoolean("collaborationError")){this.errorLine(host,item.optString("participant")+" · "+text);continue;}
            if(type.equals("agentMessage")||type.equals("assistantMessage")){
                if(!text.isEmpty())renderer.assistant(host,text,generating,item.optString("participant")+" · 尝试 "+item.optInt("attemptNumber",1),()->this.quoteMessage(text,source));
            }else{
                if (type.equals("fileReport")) {
                    JSONObject counts = item.optJSONObject("counts");
                    int total = counts == null ? 0 : counts.optInt("created", 0) + counts.optInt("modified", 0) + counts.optInt("deleted", 0);
                    // A collaboration's file inventory is published once, after the project stops.
                    if (total > 0) reports.put(item);
                    continue;
                }
                String label=RealtimeEvents.label(item,active);
                if (type.equals("reasoning") && generating && this.autoExpandedActivities.add(itemId)) this.expandedActivities.add(itemId);
                String speaker=item.optString("participant","");
                renderer.activity(host,itemId,(speaker.isEmpty()?"":speaker+" · ")+label,type,text,this.expandedActivities,RealtimeEvents.preview(item),item);
            }
            live|=generating;
        }
        String workflowStatus=turn.optString("collaborationStatus");
        if (reports.length() > 0 && collaborationFinished(workflowStatus)) {
            new ConversationRenderer(this,"").collaborationFiles(host,reports,workflowStatus);
        }
        JSONObject delivery=turn.optJSONObject("delivery");
        String deliveryLabel=delivery==null?"":CollaborationStatus.delivery(delivery.optString("status"));
        String status=ConversationCollaboration.state(workflowStatus)+" · "+turn.optInt("collaborationCompleted")+" / "+turn.optInt("collaborationTotal")
            +(deliveryLabel.isEmpty()?"":" · "+deliveryLabel);
        ConversationRenderer actions=new ConversationRenderer(this,"");
        host.addView(actions.ghost(status+" · 本轮协作",R.drawable.ic_spark,()->this.collaboration.round(turn.optString("workflowId"))));
        int omitted=turn.optInt("historyOmittedItems",0);
        if(omitted>0){TextView partial=Ui.text(this,"部分过程未保留（已淘汰 "+omitted+" 条），分页也无法找回。",11,Ui.warn,null);host.addView(partial);}
        else if(turn.optBoolean("historyPartial")){TextView partial=Ui.text(this,"部分较早过程未完整保留，已显示可恢复的记录。",11,Ui.faint,null);host.addView(partial);}
        return live||turn.optString("collaborationStatus").equals("running");
    }

    private boolean renderChildGroup(LinearLayout host, JSONObject turn, String key, ArrayList<JSONObject> group, boolean running) {
        SessionInsight.Subagent child = this.currentInsight == null ? null : this.currentInsight.child(key);
        JSONObject last = group.get(group.size() - 1);
        String status;
        boolean active;
        if (child != null) {
            status = child.status;
            active = child.active();
        } else {
            String raw = ConversationContent.string(last, "status", "");
            status = SessionInsight.normalizeStatus(raw.isEmpty() ? (running ? "inProgress" : "completed") : raw);
            active = SessionInsight.active(status);
        }
        String engine = child != null && !child.engine.isEmpty() ? child.engine
            : ConversationContent.string(last, "sourceEngine", this.renderingEngine);
        String model = child != null && !child.model.isEmpty() ? child.model
            : ConversationContent.string(last, "sourceModel", "");
        String title;
        if (child != null && !child.title.isEmpty()) title = child.title;
        else title = (engine.isEmpty() ? "子代理" : NativeUi.engineName(engine) + " 子代理");
        StringBuilder meta = new StringBuilder();
        if (!engine.isEmpty()) meta.append(NativeUi.engineName(engine));
        if (!model.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(NativeHomeActivity.shortModel(model));
        }
        if (child != null) {
            if (child.tools > 0) meta.append(meta.length() > 0 ? " · " : "").append("工具 ").append(child.tools);
            if (child.messages > 0) meta.append(meta.length() > 0 ? " · " : "").append("回复 ").append(child.messages);
            if (child.durationMs > 0) meta.append(meta.length() > 0 ? " · " : "").append(SessionInsight.duration(child.durationMs));
            else if (active && child.startedAtMs > 0) {
                long elapsed = System.currentTimeMillis() - child.startedAtMs;
                if (elapsed > 0 && elapsed < 3600000L) meta.append(meta.length() > 0 ? " · " : "").append("已运行 ").append(SessionInsight.duration(elapsed));
            }
        }
        ArrayList<String[]> rows = new ArrayList<String[]>();
        String previous = "";
        for (int i = 0; i < group.size(); i++) {
            if (rows.size() >= 40) {
                rows.add(new String[] {"… 还有 " + (group.size() - i) + " 条过程", "在右上角会话详情中查看完整记录。"});
                break;
            }
            JSONObject item = group.get(i);
            String label = RealtimeEvents.label(item, false);
            String detail = ConversationContent.item(item).trim();
            if (detail.isEmpty()) detail = RealtimeEvents.preview(item);
            String signature = label + "|" + detail;
            if (signature.equals(previous)) continue;
            previous = signature;
            rows.add(new String[] {label, detail});
        }
        if (rows.isEmpty()) rows.add(new String[] {"子任务本轮没有可展示的过程", ""});
        String preview = child != null && !child.preview.isEmpty() ? child.preview : "";
        if (preview.isEmpty()) {
            for (int i = rows.size() - 1; i >= 0 && preview.isEmpty(); i--) preview = rows.get(i)[1];
        }
        preview = SessionInsight.oneLine(preview, 220);
        final String expandId = "child:" + ConversationContent.string(turn, "id", "") + ":" + key;
        boolean open = this.expandedActivities.contains(expandId);
        Runnable toggle = () -> {
            if (!this.expandedActivities.remove(expandId)) this.expandedActivities.add(expandId);
        };
        int color = status.equals("failed") ? Ui.danger : (status.equals("interrupted") || status.equals("discarded")) ? Ui.warn
            : active ? Ui.accent : Ui.mint;
        this.transcript().subagent(host, title, meta.toString(), SessionInsight.statusLabel(status), color, active, preview, rows, open, toggle);
        return active;
    }

    private ConversationRenderer transcript() {
        String speaker = this.renderingEngine.isEmpty() ? this.engine : this.renderingEngine;
        ConversationRenderer renderer = this.agentRenderers.get(speaker);
        if (renderer == null) {
            renderer = new ConversationRenderer(this, NativeUi.engineName(speaker));
            this.agentRenderers.put(speaker, renderer);
        }
        return renderer;
    }

    private void userMessage(LinearLayout parent, String text) {
        this.transcript().user(parent, text);
    }

    /** 选图时记下系统给出的文件名，附件卡片才能显示得像文件而不是一串 UUID。 */
    private static String displayName(Activity activity, Uri uri) {
        try (android.database.Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void quoteMessage(String text) {
        this.quoteMessage(text, "");
    }

    /** 引用只进入引用条，不再把原文塞进输入框。 */
    private void quoteMessage(String text, String sender) {
        if (text == null || text.trim().isEmpty()) return;
        JSONObject value = new JSONObject();
        try {
            value.put("text", text.trim()).put("sender", sender == null ? "" : sender);
        } catch (JSONException ignored) {
        }
        this.quote = value;
        this.refreshQuote();
    }

    /** 微信式引用条：左侧强调条 + 来源 + 两行截断 + 关闭。 */
    private void refreshQuote() {
        this.updateExecutionButton();
        if (this.quoteBar == null) return;
        this.quoteBar.removeAllViews();
        if (this.quote == null) {
            this.quoteBar.setVisibility(View.GONE);
            return;
        }
        this.quoteBar.setVisibility(View.VISIBLE);
        String sender = this.quote.optString("sender", "");
        String body = this.quote.optString("text", "");
        LinearLayout row = Ui.row((Context)this);
        row.setGravity(16);
        View bar = new View((Context)this);
        bar.setBackground(Ui.round(Ui.accent, Ui.dp((Context)this, 2)));
        row.addView((View)bar, new LinearLayout.LayoutParams(Ui.dp((Context)this, 3), -1));
        LinearLayout labels = Ui.col((Context)this);
        LinearLayout.LayoutParams labelsSpace = new LinearLayout.LayoutParams(0, -2, 1.0f);
        labelsSpace.leftMargin = Ui.dp((Context)this, 10);
        row.addView((View)labels, labelsSpace);
        labels.addView(Ui.text((Context)this, sender.isEmpty() ? "\u5f15\u7528" : "\u5f15\u7528 \u00b7 " + sender, 10.5f, Ui.accent, Ui.medium((Context)this)));
        TextView bodyView = Ui.text((Context)this, body, 12.5f, Ui.muted, null);
        bodyView.setMaxLines(2);
        bodyView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bodySpace = new LinearLayout.LayoutParams(-1, -2);
        bodySpace.topMargin = Ui.dp((Context)this, 2);
        labels.addView((View)bodyView, bodySpace);
        TextView close = Ui.text((Context)this, "\u00d7", 16.0f, Ui.faint, null);
        Ui.pad(close, 10, 0, 8, 0);
        Ui.touchable(close);
        close.setOnClickListener(v -> {
            this.quote = null;
            this.refreshQuote();
        });
        row.addView((View)close);
        this.quoteBar.addView((View)row, Ui.fill());
    }

    private void editMessage(String sourceThread, JSONObject turn, JSONObject item) {
        if(this.collaboration.working()){NativeUi.toast(this,"请先等待或停止当前协作，再编辑历史消息");return;}
        if (!sourceThread.equals(this.threadId)) return;
        if (this.submitting || !this.turnId.isEmpty()) {
            NativeUi.toast(this, "请先等待或停止当前任务，再编辑消息");
            return;
        }
        LinearLayout fields = NativeUi.column(this);
        Ui.pad(fields, 20, 4, 20, 8);
        fields.addView(NativeUi.body(this, "由当前选择的 Agent 从这条消息重新回答，附件保留。后续对话保留在‘编辑前’分支；已执行的文件修改不会撤销。"));
        EditText editor = NativeUi.field(this, "消息内容", ConversationContent.editableText(item), true);
        editor.setMinLines(3);
        editor.setMaxLines(10);
        NativeUi.addField(this, fields, editor);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("编辑消息")
            .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存并重发", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
            if (this.submitting || !this.turnId.isEmpty() || !sourceThread.equals(this.threadId)) {
                NativeUi.toast(this, "会话状态已变更，请关闭后重试");
                return;
            }
            String value = editor.getText().toString();
            if (value.trim().isEmpty() && item.optJSONArray("content") != null && item.optJSONArray("content").length() <= 1
                && !ConversationContent.editableText(item).isEmpty()) {
                editor.setError("消息不能为空"); return;
            }
            this.normalizeEffort();
            this.submitting = true;
            ++this.historyGeneration;
            this.updateControls();
            dialog.setCancelable(false);
            dialog.getButton(-1).setEnabled(false);
            dialog.getButton(-2).setEnabled(false);
            NativeEngine.call("native/chat/turn/edit", NativeUi.json("engine", this.engine, "threadId", sourceThread,
                "turnId", turn.optString("id"), "itemId", item.optString("id"), "text", value,
                "model", this.model, "effort", this.effort, "clientUserMessageId", UUID.randomUUID().toString()), result -> {
                this.submitting = false;
                this.usage = null;
                this.viewCache.forget(sourceThread);
                this.resumed = true;
                this.followLatest = true;
                this.applyThreadView(result.optJSONObject("thread"));
                this.persist();
                this.refreshDrawerData();
                dialog.dismiss();
            }, error -> {
                this.submitting = false;
                this.updateControls();
                dialog.setCancelable(true);
                dialog.getButton(-1).setEnabled(true);
                dialog.getButton(-2).setEnabled(true);
                NativeUi.error(this, error);
            });
        }));
        dialog.show();
    }

    private void agentMessage(LinearLayout parent, String text, boolean streaming, String footer) {
        this.transcript().assistant(parent, text, streaming, footer);
    }

    private void errorLine(LinearLayout parent, String error) {
        String text = ErrorMessage.friendly(error);
        String act = "";
        if (error.contains("terminated")) {
            text = "\u672c\u8f6e\u8fd0\u884c\u88ab\u4e2d\u65ad\uff1aAgent \u8fdb\u7a0b\u6216\u8fde\u63a5\u610f\u5916\u7ed3\u675f\u3002\u5e38\u89c1\u539f\u56e0\u662f\u8fd0\u884c\u73af\u5883\u91cd\u542f\u3001\u5185\u5b58\u7d27\u5f20\u6216\u670d\u52a1\u88ab\u7cfb\u7edf\u56de\u6536\u3002";
            act = "\u91cd\u8bd5\u8fd9\u4e00\u8f6e";
        } else if (error.toLowerCase().contains("timeout")) {
            text = "\u672c\u8f6e\u8d85\u65f6\u672a\u5b8c\u6210\uff0c\u53ef\u80fd\u662f\u7f51\u7edc\u6216\u6a21\u578b\u54cd\u5e94\u8fc7\u6162\u3002";
            act = "\u91cd\u8bd5\u8fd9\u4e00\u8f6e";
        } else if (error.toLowerCase().contains("unauthorized") || error.contains("401")) {
            text = "API \u5bc6\u94a5\u65e0\u6548\u6216\u5df2\u8fc7\u671f\uff0c\u8bf7\u5728\u8bbe\u7f6e \u2192 API \u670d\u52a1\u914d\u7f6e \u4e2d\u68c0\u67e5\u3002";
            act = "\u53bb\u8bbe\u7f6e";
        }
        LinearLayout box = Ui.row((Context)this);
        box.setGravity(48);
        box.setBackground(Ui.round(Ui.dangerSoft, Ui.dp((Context)this, 12)));
        box.setPadding(Ui.dp((Context)this, 12), Ui.dp((Context)this, 10), Ui.dp((Context)this, 12), Ui.dp((Context)this, 10));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.topMargin = Ui.dp((Context)this, 4);
        space.bottomMargin = Ui.dp((Context)this, 8);
        ImageView warnIcon = new ImageView((Context)this);
        warnIcon.setImageResource(R.drawable.ic_warning);
        warnIcon.setColorFilter(Ui.danger);
        LinearLayout.LayoutParams iconSpace = Ui.w(Ui.dp((Context)this, 15), Ui.dp((Context)this, 15));
        iconSpace.topMargin = Ui.dp((Context)this, 1);
        iconSpace.rightMargin = Ui.dp((Context)this, 9);
        box.addView((View)warnIcon, iconSpace);
        LinearLayout stack = Ui.col((Context)this);
        TextView body = Ui.text((Context)this, text, 12.5f, Ui.danger, null);
        body.setTextIsSelectable(true);
        body.setLineSpacing(Ui.dp((Context)this, 3), 1.0f);
        stack.addView((View)body, Ui.fill());
        String action = act;
        if (!action.isEmpty()) {
            TextView retry = Ui.text((Context)this, action, 12.5f, Ui.danger, Ui.medium((Context)this));
            retry.setPadding(0, Ui.dp((Context)this, 7), 0, 0);
            Ui.touchable(retry);
            retry.setOnClickListener(v -> {
                if (action.equals("\u53bb\u8bbe\u7f6e")) {
                    this.startActivity(new Intent((Context)this, RuntimeActivity.class).putExtra("page", "api"));
                } else {
                    this.retryLastTurn();
                }
            });
            stack.addView((View)retry, Ui.fill());
        }
        box.addView((View)stack, new LinearLayout.LayoutParams(0, -2, 1.0f));
        parent.addView((View)box, space);
    }

    private void retryLastTurn() {
        if (this.thread != null) {
            JSONArray turns = this.thread.optJSONArray("turns");
            for (int i = (turns == null ? 0 : turns.length()) - 1; i >= 0; --i) {
                JSONObject turn = turns.optJSONObject(i);
                JSONArray items = turn == null ? null : turn.optJSONArray("items");
                for (int j = (items == null ? 0 : items.length()) - 1; j >= 0; --j) {
                    String text;
                    JSONObject entry;
                    JSONObject jSONObject = entry = items == null ? null : items.optJSONObject(j);
                    if (entry == null || !"userMessage".equals(ConversationContent.string(entry, "type", "")) || (text = ConversationContent.item(entry)).isEmpty()) continue;
                    this.composer.setText(text);
                    this.composer.setSelection(text.length());
                    this.sendMessage();
                    return;
                }
            }
        }
        NativeUi.toast((Activity)this, "\u627e\u4e0d\u5230\u53ef\u91cd\u8bd5\u7684\u5185\u5bb9");
    }

    private void emptyState() {
        String[][] seeds;
        this.messages.addView(new View((Context)this), Ui.fill(Ui.dp((Context)this, 8)));
        LinearLayout hero = Ui.col((Context)this);
        hero.setGravity(1);
        hero.setPadding(0, Ui.dp((Context)this, 42), 0, Ui.dp((Context)this, 8));
        this.messages.addView(hero);
        LinearLayout mark = new LinearLayout((Context)this);
        mark.setGravity(17);
        mark.setBackground(Ui.round(Ui.accentSoft, Ui.dp((Context)this, 20)));
        ImageView spark = new ImageView((Context)this);
        spark.setImageResource(R.drawable.ic_spark);
        spark.setColorFilter(Ui.onAccentSoft);
        mark.addView((View)spark, Ui.w(Ui.dp((Context)this, 24), Ui.dp((Context)this, 24)));
        LinearLayout.LayoutParams markSpace = Ui.w(Ui.dp((Context)this, 56), Ui.dp((Context)this, 56));
        markSpace.bottomMargin = Ui.dp((Context)this, 18);
        hero.addView((View)mark, markSpace);
        TextView title = Ui.display((Context)this, "\u5f00\u59cb\u4e00\u6bb5\u65b0\u7684\u5de5\u4f5c");
        title.setGravity(17);
        hero.addView(title);
        TextView caption = Ui.text((Context)this, "\u9009\u62e9\u9879\u76ee\uff0c\u63cf\u8ff0\u4efb\u52a1\uff0c" + NativeUi.engineName(this.engine) + " \u4f1a\u5728\u8fd9\u91cc\u5f00\u59cb\u5de5\u4f5c\u3002", 13.5f, Ui.muted, null);
        caption.setGravity(17);
        caption.setLineSpacing(Ui.dp((Context)this, 4), 1.0f);
        LinearLayout.LayoutParams capSpace = new LinearLayout.LayoutParams(-1, -2);
        capSpace.topMargin = Ui.dp((Context)this, 8);
        capSpace.leftMargin = Ui.dp((Context)this, 10);
        capSpace.rightMargin = Ui.dp((Context)this, 10);
        hero.addView((View)caption, capSpace);
        LinearLayout ideas = Ui.col((Context)this);
        ideas.setPadding(0, Ui.dp((Context)this, 22), 0, 0);
        for (String[] seed : seeds = new String[][]{{"\u8bfb\u61c2\u8fd9\u4e2a\u9879\u76ee", "\u5148\u6d4f\u89c8\u76ee\u5f55\uff0c\u8bf4\u660e\u9879\u76ee\u7ed3\u6784\u548c\u6280\u672f\u6808"}, {"\u4fee\u4e00\u4e2a bug", "\u627e\u51fa\u6700\u8fd1\u5931\u8d25\u7684\u5730\u65b9\u5e76\u7ed9\u51fa\u4fee\u590d\u65b9\u6848"}, {"\u5199\u4e00\u4e2a\u5c0f\u5de5\u5177", "\u6309\u6211\u7684\u9700\u6c42\u5199\u51fa\u811a\u672c\u5e76\u8dd1\u901a"}}) {
            LinearLayout card = Ui.col((Context)this);
            card.setBackground(Ui.round((Context)this, Ui.card, 16.0f, 0.8f, Ui.lineSoft));
            card.setPadding(Ui.dp((Context)this, 16), Ui.dp((Context)this, 13), Ui.dp((Context)this, 16), Ui.dp((Context)this, 13));
            LinearLayout.LayoutParams cardSpace = new LinearLayout.LayoutParams(-1, -2);
            cardSpace.bottomMargin = Ui.dp((Context)this, 8);
            card.setLayoutParams(cardSpace);
            TextView ideaTitle = Ui.text((Context)this, seed[0], 14.0f, Ui.text, Ui.medium((Context)this));
            ideaTitle.setPadding(Ui.dp((Context)this, 2), Ui.dp((Context)this, 1), Ui.dp((Context)this, 2), Ui.dp((Context)this, 1));
            card.addView(ideaTitle, Ui.fill());
            TextView note = Ui.text((Context)this, seed[1], 12.0f, Ui.faint, null);
            LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
            noteSpace.topMargin = Ui.dp((Context)this, 3);
            card.addView((View)note, noteSpace);
            Ui.touchable(ideaTitle);
            Ui.rippleRounded(ideaTitle, 8, Ui.text);
            ideaTitle.setOnClickListener(v -> {
                this.composer.setText(seed[0] + "\uff1a");
                this.composer.setSelection(this.composer.getText().length());
                this.composer.requestFocus();
            });
            ideas.addView(card);
        }
        this.messages.addView(ideas);
    }

    private void hintScrollButton() {
        if (this.scrollBottom == null) {
            return;
        }
        boolean show = !this.followLatest && this.messages.getChildCount() > 0 && this.thread != null;
        if (show == (this.scrollBottom.getVisibility() == View.VISIBLE)) {
            return;
        }
        this.scrollBottom.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show) {
            this.scrollBottom.setAlpha(0.0f);
            this.scrollBottom.setTranslationY(Ui.dp((Context)this, 8));
            this.scrollBottom.animate().alpha(1.0f).translationY(0.0f).setDuration(160L).start();
        }
    }

    /** 回到最新消息按钮：抬到输入卡片上方，否则会被 composer 盖住看不见。 */
    private void placeScrollButton() {
        if (this.scrollBottom == null || this.composerHost == null) {
            return;
        }
        ViewGroup.LayoutParams raw = this.scrollBottom.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) {
            return;
        }
        int strip = this.statusStrip != null && this.statusStrip.getVisibility() == View.VISIBLE ? this.statusStrip.getHeight() : 0;
        FrameLayout.LayoutParams space = (FrameLayout.LayoutParams)raw;
        int bottom = this.composerHost.getHeight() + strip + Ui.dp((Context)this, 10);
        if (space.bottomMargin != bottom) {
            space.bottomMargin = bottom;
            this.scrollBottom.setLayoutParams(space);
        }
        ViewGroup parent = (ViewGroup)this.scrollBottom.getParent();
        if (parent != null && parent.getChildCount() > 0 && parent.getChildAt(parent.getChildCount() - 1) != this.scrollBottom) {
            this.scrollBottom.bringToFront();
        }
    }

    private void status(String message, int color2, boolean visible) {
        if (this.taskCapsule == null || this.statusText == null || this.taskCapsuleDot == null) {
            return;
        }
        if (!visible || message == null || message.isEmpty()) {
            this.taskCapsule.setVisibility(View.GONE);
            return;
        }
        this.taskCapsule.setVisibility(View.VISIBLE);
        this.taskCapsuleDot.setBackground(Ui.round(color2, Ui.dp((Context)this, 3)));
        this.statusText.setText(message);
        this.statusText.setTextColor(Ui.muted);
        this.taskCapsule.setContentDescription(message);
    }

    private void statusIdle() {
        if (this.loading) {
            return;
        }
        if (!NativeEngine.ready()) {
            this.status("Agent \u670d\u52a1\u5c1a\u672a\u8fde\u63a5", Ui.warn, true);
            return;
        }
        this.updateStatusLine();
    }

    private void startTurnClock() {
        this.turnStartedAt = SystemClock.elapsedRealtime();
        this.handler.removeCallbacks(this.ticker);
        this.handler.post(this.ticker);
        this.updateStatusLine();
    }

    private void stopTurnClock() {
        this.turnStartedAt = 0L;
        this.handler.removeCallbacks(this.ticker);
        this.updateStatusLine();
    }

    private void updateStatusLine() {
        String execution=this.executionState();
        if(execution.equals("stopping")||execution.equals("pausing")||execution.equals("interrupted")||execution.equals("paused")||execution.equals("resuming")){
            String message=execution.equals("stopping")||execution.equals("pausing")?"正在停止当前执行…":execution.equals("resuming")?"等待继续原任务…":
                    this.autoQueuePending()?"当前执行已停止 · 待发消息自动接力":this.pausedQueueWaiting()?"当前执行已停止 · 待发队列已暂停":"当前执行已停止 · 输入为空时点击执行可继续";
            this.status(message,this.autoQueuePending()?Ui.accent:Ui.warn,true);return;
        }
        if(this.turnId.isEmpty()&&this.collaboration!=null&&this.collaboration.working()){this.status(this.collaboration.progress(),Ui.accent,true);return;}
        if (!this.turnId.isEmpty()) {
            long elapsed = this.turnStartedAt > 0L ? SystemClock.elapsedRealtime() - this.turnStartedAt : 0L;
            JSONObject turn = this.currentTurn(this.turnId);
            JSONObject progress = turn == null ? null : turn.optJSONObject("progress");
            this.status(NativeUi.engineName(this.engine) + " · " + TurnProgressText.title(progress) + " · "
                + NativeHomeActivity.elapsedText(elapsed), progress != null && "retrying".equals(progress.optString("phase")) ? Ui.warn : Ui.accent, true);
            return;
        }
        this.status("", Ui.muted, false);
    }

    private static String elapsedText(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        long hours = total / 3600L;
        long minutes = total % 3600L / 60L;
        long seconds = total % 60L;
        return hours > 0L ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds) : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static int faded(int color2, float factor) {
        return Color.argb(Math.round((float)Color.alpha(color2) * factor), Color.red(color2), Color.green(color2), Color.blue(color2));
    }

    private View usageDivider() {
        View divider = new View((Context)this);
        divider.setBackgroundColor(Ui.lineSoft);
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(Ui.dp((Context)this, 1), Ui.dp((Context)this, 26));
        space.setMargins(Ui.dp((Context)this, 8), 0, 0, 0);
        divider.setLayoutParams(space);
        return divider;
    }

    private int[] sessionCounts() {
        int turns = 0;
        int messages = 0;
        JSONArray itemsTurns = this.thread == null ? null : this.thread.optJSONArray("turns");
        if (itemsTurns != null) {
            turns = itemsTurns.length();
            for (int i = 0; i < itemsTurns.length(); ++i) {
                JSONObject turn = itemsTurns.optJSONObject(i);
                JSONArray items = turn == null ? null : turn.optJSONArray("items");
                if (items == null) continue;
                for (int j = 0; j < items.length(); ++j) {
                    JSONObject item = items.optJSONObject(j);
                    String type = item == null ? "" : item.optString("type", "");
                    if (type.equals("userMessage") || type.equals("agentMessage")) ++messages;
                }
            }
        }
        return new int[]{turns, messages};
    }

    private static int totalTokens(JSONObject total) {
        if (total == null) return 0;
        int value = (int)total.optDouble("totalTokens", 0.0);
        if (value > 0) return value;
        return (int)total.optDouble("inputTokens", 0.0) + (int)total.optDouble("outputTokens", 0.0);
    }

    /** 上下文占用只取输入侧；DeepSeek 只给 totalTokens 时才回退。 */
    private static int contextTokens(JSONObject last) {
        if (last == null) return 0;
        int input = (int)last.optDouble("inputTokens", 0.0);
        return input > 0 ? input : (int)last.optDouble("totalTokens", 0.0);
    }

    /** 不同模型的上下文窗口不同；已知模型优先，其次才用引擎上报值。 */
    private int contextWindow() {
        JSONObject tokens = this.usage == null ? null : this.usage.optJSONObject("tokenUsage");
        int provided = tokens == null ? 0 : (int)tokens.optDouble("modelContextWindow", 0.0);
        return NativeHomeActivity.modelContextWindow(this.engine, this.model, provided);
    }

    private static int modelContextWindow(String engine, String model, int provided) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if ("deepseek".equals(engine) || m.contains("deepseek")) {
            if (m.contains("flash")) return 262144;
            if (m.contains("reasoner") || m.contains("r1")) return 131072;
            if (m.contains("chat") || m.contains("v3")) return 131072;
        }
        if (m.contains("claude")) {
            if (provided > 0) return provided;
            if (m.contains("1m") || m.contains("[1m]")) return 1000000;
            return 200000;
        }
        if (provided > 0) return provided;
        if (m.contains("gpt-5") || m.contains("codex")) return 400000;
        if (m.contains("gemini")) return 1000000;
        return 128000;
    }

    private String contextPolicy() {
        if ("codex".equals(this.engine)) return "Codex \u5f15\u64ce\u81ea\u52a8\u538b\u7f29";
        if ("claude".equals(this.engine)) return "Claude Code \u81ea\u52a8\u538b\u7f29";
        if ("deepseek".equals(this.engine)) return "DSH \u81ea\u52a8\u538b\u7f29";
        return "\u5f15\u64ce\u81ea\u52a8\u538b\u7f29";
    }

    private int sessionSteps() {
        JSONArray turns;
        int steps = 0;
        if (this.thread != null && (turns = this.thread.optJSONArray("turns")) != null) {
            for (int i = 0; i < turns.length(); ++i) {
                JSONObject turn = turns.optJSONObject(i);
                JSONArray items = turn == null ? null : turn.optJSONArray("items");
                if (items == null) continue;
                for (int j = 0; j < items.length(); ++j) {
                    JSONObject item = items.optJSONObject(j);
                    String type = item == null ? "" : item.optString("type", "");
                    if (type.equals("agentMessage") || (!type.equals("userMessage") && !type.equals("agentMessage"))) ++steps;
                }
            }
        }
        return steps;
    }

    private JSONObject usageTotals() {
        JSONObject tokens = this.usage == null ? null : this.usage.optJSONObject("tokenUsage");
        if (tokens == null) return null;
        JSONObject total = tokens.optJSONObject("total");
        return NativeHomeActivity.totalTokens(total) > 0 ? total : tokens.optJSONObject("last");
    }

    private JSONArray usageModels() {
        return this.usage == null ? null : this.usage.optJSONArray("byModel");
    }

    private static String usageModelName(JSONObject group) {
        String model = group.optString("model", "");
        if (model.equals("null") || model.equalsIgnoreCase("undefined")) model = "";
        String engine = group.optString("engine", "");
        String label = model.isEmpty() ? "未记录模型" : NativeHomeActivity.shortModel(model);
        return engine.isEmpty() ? label : NativeUi.engineName(engine) + " · " + label;
    }

    private static String usageModelState(JSONObject group) {
        if (group.optBoolean("isActive")) return "执行中";
        if (group.optBoolean("isCurrent")) return "当前";
        if (group.optBoolean("isConfigured")) return "协作成员";
        if (group.optBoolean("historical")) return "历史";
        return "已使用";
    }

    /** Current and collaborator models only; retired models collapse into one summary line. */
    private void modelUsageRows(LinearLayout box, boolean withContext) {
        JSONArray models = this.usageModels();
        if (models == null || models.length() == 0) return;
        boolean collaboration = this.collaboration != null && this.collaboration.enabled();
        if (!collaboration && this.thread != null && "collaboration".equals(this.thread.optString("executionMode"))) collaboration = true;
        java.util.ArrayList<JSONObject> visible = new java.util.ArrayList<>();
        int retired = 0, retiredTokens = 0;
        for (int i = 0; i < models.length(); i++) {
            JSONObject group = models.optJSONObject(i);
            if (group == null) continue;
            boolean current = group.optBoolean("isCurrent") || group.optBoolean("isActive");
            boolean keep = collaboration ? (current || group.optBoolean("isConfigured")) : current;
            if (keep) { visible.add(group); continue; }
            retired++;
            JSONObject usage = group.optJSONObject("tokenUsage");
            if (usage != null) retiredTokens += NativeHomeActivity.totalTokens(usage.optJSONObject("total"));
        }
        if (visible.isEmpty() && retired == 0) return;
        if (!visible.isEmpty()) {
            box.addView(this.section(collaboration ? "模型" : "当前模型"));
            for (int i = 0; i < visible.size(); i++) {
                JSONObject group = visible.get(i);
                JSONObject context = group.optJSONObject("context");
                JSONObject usage = group.optJSONObject("tokenUsage");
                int tokens = usage == null ? 0 : NativeHomeActivity.totalTokens(usage.optJSONObject("total"));
                int used = context == null ? 0 : (int)context.optDouble("usedTokens", 0);
                int window = context == null ? 0 : (int)context.optDouble("windowTokens", 0);
                StringBuilder value = new StringBuilder();
                if (tokens > 0) value.append(NativeHomeActivity.tokenText(tokens)).append(" tokens");
                if (withContext && window > 0) {
                    if (value.length() > 0) value.append(" · ");
                    value.append(Math.min(100, Math.round(used * 100.0f / window))).append("% 上下文");
                }
                if (value.length() == 0) value.append("—");
                String state = collaboration ? NativeHomeActivity.usageModelState(group)
                        : (group.optBoolean("isActive") ? "执行中" : "当前");
                int color = group.optBoolean("isActive") ? Ui.accent : Ui.text;
                LinearLayout row = this.metricRow(NativeHomeActivity.usageModelName(group) + " · " + state, value.toString(), color);
                TextView rowLabel = (TextView)row.getChildAt(0);
                rowLabel.setSingleLine(true);
                rowLabel.setEllipsize(TextUtils.TruncateAt.END);
                box.addView(row, Ui.fill());
            }
        }
        if (collaboration && retired > 0) {
            LinearLayout row = this.metricRow("其他历史模型 · " + retired + " 个",
                    retiredTokens > 0 ? NativeHomeActivity.tokenText(retiredTokens) + " tokens" : "—", Ui.faint);
            TextView label = (TextView)row.getChildAt(0);
            label.setSingleLine(true);
            box.addView(row, Ui.fill());
        }
    }

    private int turnStatsTokenSum() {
        return (int)ConversationContent.sumTurnStat(this.thread, this.turnStats(), "tokens", "billedTokens");
    }

    private int sessionTokenTotal() {
        JSONObject tokens = this.usage == null ? null : this.usage.optJSONObject("tokenUsage");
        if (tokens == null) return this.turnStatsTokenSum();
        int value = NativeHomeActivity.totalTokens(tokens.optJSONObject("total"));
        if (value > 0) return value;
        int sum = this.turnStatsTokenSum();
        if (sum > 0) return sum;
        return 0;
    }

    private int cacheHitPercent() {
        JSONObject stats = this.usageTotals();
        if (stats == null) return 0;
        int input = (int)stats.optDouble("inputTokens", 0.0);
        int cached = (int)stats.optDouble("cachedInputTokens", 0.0);
        if (input <= 0 || cached <= 0) return 0;
        return Math.min(100, Math.round(cached * 100.0f / input));
    }

    private long sessionTurnMillis() {
        return ConversationContent.sumTurnStat(this.thread, this.turnStats(), "ms", "durationMs");
    }

    private void updateUsageRow() {
        if (this.usageText == null || this.usageRow == null) {
            return;
        }
        JSONObject tokens = this.usage == null ? null : this.usage.optJSONObject("tokenUsage");
        JSONObject last = tokens == null ? null : tokens.optJSONObject("last");
        int used = NativeHomeActivity.contextTokens(last);
        int window = this.contextWindow();
        float ratio = window > 0 ? Math.min(1.0f, (float)used / (float)window) : 0.0f;

        if (this.usage != null && this.usage.optBoolean("contextPending")) {
            this.usageText.setText("待交接");
            this.usageText.setTextColor(Ui.muted);
            if (this.usageRing != null) this.usageRing.setProgress(0f, Ui.faint);
        } else if (used > 0 && window > 0) {
            int percent = Math.min(100, Math.round(ratio * 100.0f));
            this.usageText.setText(percent + "%");
            this.usageText.setTextColor(ratio >= 0.9f ? Ui.danger : Ui.muted);
            if (this.usageRing != null) {
                this.usageRing.setProgress(ratio, ratio >= 0.9f ? Ui.danger : (ratio >= 0.7f ? Ui.warn : Ui.accent));
            }
        } else {
            this.usageText.setText("--");
            this.usageText.setTextColor(Ui.faint);
            if (this.usageRing != null) {
                this.usageRing.setProgress(0.0f, Ui.faint);
            }
        }

        int totalTokens = this.sessionTokenTotal();
        int cacheHit = this.cacheHitPercent();
        if (this.turnText != null) {
            boolean hasTokens = totalTokens > 0;
            this.turnText.setText(hasTokens
                    ? NativeHomeActivity.tokenText(totalTokens) + (cacheHit > 0 ? " · " + cacheHit + "%" : "")
                    : "—");
            this.turnText.setTextColor(hasTokens ? Ui.muted : Ui.faint);
        }

        int[] counts = this.sessionCounts();
        int steps = this.sessionSteps();
        String stats = counts[0] > 0 ? counts[0] + " 轮" : "";
        if (steps > 0) {
            stats += (stats.isEmpty() ? "" : " · ") + steps + " 步";
        }
        if (this.sessionStats != null) {
            this.sessionStats.setText(stats.isEmpty() ? "—" : stats);
            this.sessionStats.setTextColor(stats.isEmpty() ? Ui.faint : Ui.muted);
        }
    }

    private void bindStatsChip(LinearLayout chip, String description, Runnable click) {
        Ui.touchable(chip);
        Ui.rippleRounded(chip, 12, Ui.text);
        chip.setOnClickListener(v -> click.run());
        chip.setContentDescription(description);
    }

    private View statDivider() {
        View divider = new View((Context)this);
        divider.setBackgroundColor(Ui.lineSoft);
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(
                Math.max(1, Ui.dp((Context)this, 0.6f)), Ui.dp((Context)this, 18));
        space.gravity = Gravity.CENTER_VERTICAL;
        divider.setLayoutParams(space);
        return divider;
    }

    private void taskCapsulePopup() {
        if (this.turnId.isEmpty()) {
            return;
        }
        long elapsed = this.turnStartedAt > 0L ? SystemClock.elapsedRealtime() - this.turnStartedAt : 0L;
        String action = this.currentTaskAction();
        this.showStatsPopup(this.taskCapsule, "当前任务", box -> {
            box.addView(Ui.text((Context)this, NativeHomeActivity.elapsedText(elapsed), 24.0f, Ui.text, Ui.medium((Context)this)));
            LinearLayout.LayoutParams subtitleSpace = new LinearLayout.LayoutParams(-1, -2);
            subtitleSpace.topMargin = Ui.dp((Context)this, -2);
            box.addView((View)Ui.text((Context)this, "运行中 · " + NativeUi.engineName(this.engine),
                    11.5f, Ui.accent, Ui.medium((Context)this)), subtitleSpace);
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2);
            first.topMargin = Ui.dp((Context)this, 8);
            box.addView((View)this.metricRow("模型", this.model.isEmpty() ? "引擎默认" : NativeHomeActivity.shortModel(this.model)), first);
            box.addView((View)this.metricRow("已运行", NativeHomeActivity.elapsedText(elapsed)), Ui.fill());
            if (!action.isEmpty()) {
                box.addView((View)this.metricRow("当前动作", action, Ui.accent), Ui.fill());
            }
            JSONObject current = this.currentTurn(this.turnId);
            JSONObject progress = current == null ? null : current.optJSONObject("progress");
            if (progress != null) {
                long lastAt = (long)(progress.optDouble("updatedAt", 0) * 1000);
                if (lastAt > 0) box.addView(this.metricRow("最近事件", elapsedText(Math.max(0, System.currentTimeMillis() - lastAt)) + " 前"));
                if ("waiting".equals(progress.optString("phase")))
                    box.addView(Ui.text(this, "等待接口返回；尚无新的思考、回复或工具事件。", 11.5f, Ui.muted, null));
                String retry = TurnProgressText.retryDetail(progress);
                if (!retry.isEmpty()) box.addView(this.metricRow("重试信息", retry));
            }
            JSONArray activity = current == null ? null : current.optJSONArray("activity");
            if (activity != null && activity.length() > 0) {
                StringBuilder timeline = new StringBuilder();
                for (int i = Math.max(0, activity.length() - 6); i < activity.length(); i++) {
                    JSONObject entry = activity.optJSONObject(i);
                    if (entry != null) timeline.append(new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                        .format(new java.util.Date((long)(entry.optDouble("updatedAt") * 1000))))
                        .append("  ").append(TurnProgressText.title(entry)).append('\n');
                }
                box.addView(Ui.text(this, timeline.toString().trim(), 11.5f, Ui.muted, null));
            }
        });
        this.taskProgressPopup = true;
    }

    private String currentTaskAction() {
        if (!this.stoppingTurn.isEmpty()) return "正在停止";
        JSONArray turns;
        if (this.thread == null || (turns = this.thread.optJSONArray("turns")) == null || turns.length() == 0) {
            return "";
        }
        JSONObject turn = turns.optJSONObject(turns.length() - 1);
        if (turn != null && turn.optJSONObject("progress") != null) return TurnProgressText.title(turn.optJSONObject("progress"));
        JSONArray items = turn == null ? null : turn.optJSONArray("items");
        if (items == null) {
            return "";
        }
        for (int i = items.length() - 1; i >= 0; --i) {
            JSONObject entry = items.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String type = ConversationContent.string(entry, "type", "");
            String status = ConversationContent.string(entry, "status", "");
            if (type.equals("agentMessage") || type.equals("userMessage")) {
                continue;
            }
            if (status.equals("inProgress") || type.equals("reasoning")) {
                if (type.equals("reasoning")) return "思考中";
                if (type.equals("commandExecution")) return "运行命令";
                if (type.equals("fileChange")) return "修改文件";
                return "调用工具";
            }
        }
        return "";
    }

    /** 三块统计共用的轻量浮层：挂在 DecorView 上，切换时原地换内容。 */
    private void showStatsPopup(View anchor, String title, Consumer<LinearLayout> fill) {
        this.taskProgressPopup = false;
        if (anchor == null) {
            return;
        }
        int screenW = this.getResources().getDisplayMetrics().widthPixels;
        int screenH = this.getResources().getDisplayMetrics().heightPixels;
        int margin = Ui.dp((Context)this, 16);
        int width = Math.min(Ui.dp((Context)this, 280),
                Math.max(Ui.dp((Context)this, 220), screenW - margin * 2));

        LinearLayout card = this.statsPopup;
        boolean fresh = card == null;
        if (fresh) {
            card = Ui.col((Context)this);
            card.setBackground(Ui.round((Context)this, Ui.cardHigh, 18.0f, 1.0f, Ui.line));
            Ui.pad(card, 16, 14, 16, 14);
            card.setClickable(true);
            card.setElevation(Ui.dp((Context)this, 12));
            this.statsPopup = card;
        } else {
            card.removeAllViews();
        }
        card.addView(Ui.text((Context)this, title, 15.0f, Ui.text, Ui.medium((Context)this)));
        fill.accept(card);

        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = card.getMeasuredHeight();
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int x = location[0] + (anchor.getWidth() - width) / 2;
        x = Math.max(margin, Math.min(x, screenW - width - margin));
        int gap = Ui.dp((Context)this, 8);
        int y = location[1] - height - gap;
        if (y < margin) {
            y = location[1] + anchor.getHeight() + gap;
        }
        if (y + height > screenH - margin) {
            y = Math.max(margin, screenH - height - margin);
        }

        if (fresh) {
            ViewGroup decor = (ViewGroup)this.getWindow().getDecorView();
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
            params.gravity = Gravity.TOP | Gravity.START;
            params.leftMargin = x;
            params.topMargin = y;
            decor.addView(card, params);
            card.bringToFront();
            this.backCallback.setEnabled(true);
        } else {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
            params.gravity = Gravity.TOP | Gravity.START;
            params.leftMargin = x;
            params.topMargin = y;
            card.setLayoutParams(params);
            card.requestLayout();
        }
    }

    private void dismissStatsPopup() {
        this.taskProgressPopup = false;
        if (this.statsPopup == null) {
            return;
        }
        ViewGroup parent = this.statsPopup.getParent() instanceof ViewGroup
                ? (ViewGroup)this.statsPopup.getParent() : null;
        if (parent != null) {
            parent.removeView(this.statsPopup);
        }
        this.statsPopup = null;
        if (!this.drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
            this.backCallback.setEnabled(false);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && this.statsPopup != null) {
            float x = event.getRawX();
            float y = event.getRawY();
            if (!this.touchInsideStatsChip(x, y) && !this.touchInsideView(this.statsPopup, x, y)) {
                this.dismissStatsPopup();
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean touchInsideStatsChip(float x, float y) {
        View[] chips = new View[]{this.contextChip, this.tokenChip, this.sessionChip};
        for (View chip : chips) {
            if (chip != null && this.touchInsideView(chip, x, y)) {
                return true;
            }
        }
        return false;
    }

    private boolean touchInsideView(View view, float x, float y) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return x >= location[0] && x <= location[0] + view.getWidth()
                && y >= location[1] && y <= location[1] + view.getHeight();
    }

    private void hideKeyboard() {
        View focus = this.getCurrentFocus();
        if (focus == null) return;
        InputMethodManager imm = (InputMethodManager)this.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private LinearLayout metricRow(String label, String value) {
        return this.metricRow(label, value, Ui.text);
    }

    private LinearLayout metricRow(String label, String value, int color) {
        LinearLayout row = Ui.row((Context)this);
        row.setGravity(16);
        row.setPadding(0, Ui.dp((Context)this, 6), 0, Ui.dp((Context)this, 6));
        row.addView((View)Ui.text((Context)this, label, 12.5f, Ui.muted, null),
                new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(Ui.text((Context)this, value, 12.5f, color, Ui.medium((Context)this)));
        return row;
    }

    private TextView popupNote(String text, int color) {
        TextView note = Ui.text((Context)this, text, 11.5f, color, null);
        note.setLineSpacing(Ui.dp((Context)this, 3), 1.0f);
        note.setPadding(0, Ui.dp((Context)this, 8), 0, 0);
        return note;
    }

    private void contextPopup() {
        JSONObject tokens = this.usage == null ? null : this.usage.optJSONObject("tokenUsage");
        JSONObject last = tokens == null ? null : tokens.optJSONObject("last");
        int used = NativeHomeActivity.contextTokens(last);
        int window = this.contextWindow();
        boolean stale = this.usage != null && this.usage.optBoolean("contextStale", false);
        int percent = window > 0 ? Math.min(100, Math.round((float)used * 100.0f / (float)window)) : 0;
        int tint = percent >= 90 ? Ui.danger : (percent >= 70 ? Ui.warn : Ui.accent);
        this.showStatsPopup(this.contextChip, "上下文", box -> {
            if (this.usage != null && this.usage.optBoolean("contextPending")) {
                box.addView(this.popupNote("完整对话已保留，下一次发送会自动交给当前 Agent。当前 Agent 的实际占用将在接口返回后更新。", Ui.muted));
                String source = NativeUi.engineName(this.usage.optString("contextEngine", this.engine));
                box.addView(this.metricRow("上次上下文 · " + source, used > 0 ? tokenText(used) + " tokens" : "未上报"));
                box.addView(this.metricRow("当前模型窗口", tokenText(window) + " tokens"));
                this.modelUsageRows(box, true);
                return;
            }
            if (used <= 0 || window <= 0) {
                box.addView(this.popupNote("还没有上下文数据。", Ui.faint));
                box.addView((View)this.metricRow("窗口", window > 0 ? NativeHomeActivity.tokenText(window) + " tokens" : "—"), Ui.fill());
                box.addView((View)this.metricRow("模型", this.model.isEmpty() ? "未选择" : NativeHomeActivity.shortModel(this.model)), Ui.fill());
                box.addView((View)this.metricRow("压缩策略", this.contextPolicy()), Ui.fill());
                box.addView(this.popupNote("窗口按当前模型计算：" + NativeHomeActivity.shortModel(this.model) + "。不同模型窗口不同。", Ui.faint));
                this.modelUsageRows(box, true);
                return;
            }
            LinearLayout hero = Ui.row((Context)this);
            hero.setGravity(16);
            hero.setBackground(Ui.round(Ui.card, Ui.dp((Context)this, 14)));
            Ui.pad(hero, 12, 12, 12, 12);
            ContextRing ring = new ContextRing((Context)this);
            ring.setProgress(Math.min(1.0f, (float)used / (float)window), tint);
            hero.addView((View)ring, Ui.w(Ui.dp((Context)this, 40), Ui.dp((Context)this, 40)));
            LinearLayout heroLabels = Ui.col((Context)this);
            LinearLayout.LayoutParams heroSpace = new LinearLayout.LayoutParams(0, -2, 1.0f);
            heroSpace.leftMargin = Ui.dp((Context)this, 12);
            hero.addView((View)heroLabels, heroSpace);
            heroLabels.addView(Ui.text((Context)this, percent + "%", 26.0f, Ui.text, Ui.medium((Context)this)));
            heroLabels.addView(Ui.text((Context)this,
                    "~" + NativeHomeActivity.tokenText(used) + " / " + NativeHomeActivity.tokenText(window),
                    11.5f, Ui.muted, null));
            LinearLayout.LayoutParams heroBox = new LinearLayout.LayoutParams(-1, -2);
            heroBox.topMargin = Ui.dp((Context)this, 8);
            box.addView((View)hero, heroBox);
            box.addView((View)this.metricRow("已用", NativeHomeActivity.tokenText(used) + " tokens"), Ui.fill());
            box.addView((View)this.metricRow("窗口", NativeHomeActivity.tokenText(window) + " tokens"), Ui.fill());
            box.addView((View)this.metricRow("模型", this.model.isEmpty() ? "未选择" : NativeHomeActivity.shortModel(this.model)), Ui.fill());
            box.addView((View)this.metricRow("压缩状态", stale ? "已压缩" : (percent >= 80 ? "接近上限" : "正常"), stale || percent >= 80 ? Ui.warn : Ui.muted), Ui.fill());
            box.addView((View)this.metricRow("压缩策略", this.contextPolicy()), Ui.fill());
            box.addView(this.popupNote("窗口按当前模型计算：" + NativeHomeActivity.shortModel(this.model)
                    + " → " + NativeHomeActivity.tokenText(window) + "。" + this.contextPolicy()
                    + "；不同模型窗口不同，压缩由引擎按窗口触发。", Ui.faint));
            this.modelUsageRows(box, true);
        });
    }

    private void tokenPopup() {
        JSONObject tokens = this.usage == null ? null : this.usage.optJSONObject("tokenUsage");
        JSONObject total = tokens == null ? null : tokens.optJSONObject("total");
        JSONObject stats = this.usageTotals();
        int billed = this.sessionTokenTotal();
        int input = stats == null ? 0 : (int)stats.optDouble("inputTokens", 0.0);
        int output = stats == null ? 0 : (int)stats.optDouble("outputTokens", 0.0);
        int cached = stats == null ? 0 : (int)stats.optDouble("cachedInputTokens", 0.0);
        int write = stats == null ? 0 : (int)stats.optDouble("cacheWriteInputTokens", stats.optDouble("cacheWriteTokens", 0.0));
        int cacheHit = this.cacheHitPercent();
        boolean detailCumulative = NativeHomeActivity.totalTokens(total) > 0;
        boolean cumulative = detailCumulative || this.turnStatsTokenSum() > 0;
        String inputLabel = detailCumulative ? "输入" : "本轮输入";
        String cachedLabel = detailCumulative ? "缓存读取" : "本轮缓存读取";
        String outputLabel = detailCumulative ? "输出" : "本轮输出";
        String hitLabel = detailCumulative ? "缓存命中" : "本轮缓存命中";
        this.showStatsPopup(this.tokenChip, "Token 用量", box -> {
            if (billed <= 0) {
                box.addView(this.popupNote("该引擎还没有返回 Token 用量。", Ui.faint));
                return;
            }
            box.addView(Ui.text((Context)this, NativeHomeActivity.tokenText(billed), 24.0f, Ui.text, Ui.medium((Context)this)));
            LinearLayout.LayoutParams subSpace = new LinearLayout.LayoutParams(-1, -2);
            subSpace.topMargin = Ui.dp((Context)this, -2);
            box.addView((View)Ui.text((Context)this,
                    (cumulative ? "累计" : "本轮") + (cacheHit > 0 ? " · 缓存命中 " + cacheHit + "%" : ""),
                    11.5f, Ui.faint, null), subSpace);
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2);
            first.topMargin = Ui.dp((Context)this, 8);
            if (cacheHit > 0) {
                box.addView((View)this.metricRow(hitLabel, cacheHit + "%"), first);
            }
            box.addView((View)this.metricRow(inputLabel, NativeHomeActivity.tokenText(Math.max(0, input - cached))), Ui.fill());
            box.addView((View)this.metricRow(cachedLabel, NativeHomeActivity.tokenText(cached)), Ui.fill());
            if (write > 0) {
                box.addView((View)this.metricRow("缓存写入", NativeHomeActivity.tokenText(write)), Ui.fill());
            }
            box.addView((View)this.metricRow(outputLabel, NativeHomeActivity.tokenText(output)), Ui.fill());
            this.modelUsageRows(box, false);
            if (this.usage != null && this.usage.optBoolean("totalsPartial"))
                box.addView(this.popupNote("累计包含各 Agent 已上报的用量；仅上报上下文占用的记录不会当作计费用量累加。", Ui.muted));
            box.addView(this.popupNote(detailCumulative
                    ? "输入不含缓存读取；总量 = 输入 + 缓存读取 + 输出。"
                    : (cumulative
                        ? "累计总量由本机按轮次汇总；输入 / 缓存 / 输出仅最近一轮。"
                        : "该引擎只返回近期明细，暂无会话累计。"), Ui.faint));
        });
    }

    private void sessionPopup() {
        int[] counts = this.sessionCounts();
        int steps = this.sessionSteps();
        int tokens = this.sessionTokenTotal();
        long millis = this.sessionTurnMillis();
        this.showStatsPopup(this.sessionChip, "会话统计", box -> {
            box.addView(Ui.text((Context)this, counts[0] + " 轮", 24.0f, Ui.text, Ui.medium((Context)this)));
            LinearLayout.LayoutParams subSpace = new LinearLayout.LayoutParams(-1, -2);
            subSpace.topMargin = Ui.dp((Context)this, -2);
            box.addView((View)Ui.text((Context)this,
                    steps > 0 ? steps + " 步" : "本会话",
                    11.5f, Ui.faint, null), subSpace);
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2);
            first.topMargin = Ui.dp((Context)this, 8);
            box.addView((View)this.metricRow("步数", steps > 0 ? steps + " 步" : "—"), first);
            box.addView((View)this.metricRow("累计 Token", tokens > 0 ? NativeHomeActivity.tokenText(tokens) : "—"), Ui.fill());
            box.addView((View)this.metricRow("总用时", millis > 0 ? NativeHomeActivity.elapsedText(millis) : "—"), Ui.fill());
            JSONArray allModels = this.usageModels();
            int liveModels = 0, retiredModels = 0;
            for (int i = 0; allModels != null && i < allModels.length(); i++) {
                JSONObject g = allModels.optJSONObject(i);
                if (g == null) continue;
                if (g.optBoolean("isCurrent") || g.optBoolean("isConfigured") || g.optBoolean("isActive")) liveModels++;
                else retiredModels++;
            }
            box.addView((View)this.metricRow("模型", this.collaboration != null && this.collaboration.enabled() && liveModels > 1
                    ? "协作 · " + liveModels + " 个模型" + (retiredModels > 0 ? " · 历史 " + retiredModels : "")
                    : this.model.isEmpty() ? "未选择" : NativeHomeActivity.shortModel(this.model)), Ui.fill());
            box.addView((View)this.metricRow("引擎", NativeUi.engineName(this.engine)), Ui.fill());
            this.modelUsageRows(box, false);
            box.addView(this.popupNote("总用时按本机记录；模型用时、工具用时与 TTFT 需引擎上报。", Ui.faint));
        });
    }

    private void usageSheet() {
        if (this.usage == null) {
            NativeUi.toast((Activity)this, "还没有可查看的统计");
            return;
        }
        JSONObject tokens = this.usage.optJSONObject("tokenUsage");
        JSONObject last = tokens == null ? null : tokens.optJSONObject("last");
        JSONObject total = tokens == null ? null : tokens.optJSONObject("total");
        int used = NativeHomeActivity.contextTokens(last);
        int window = tokens == null ? 0 : (int)tokens.optDouble("modelContextWindow", 0.0);
        int percent = window > 0 ? Math.min(100, Math.round((float)used * 100.0f / (float)window)) : 0;
        int tint = percent >= 90 ? Ui.danger : (percent >= 70 ? Ui.warn : Ui.accent);
        int[] counts = this.sessionCounts();
        int cumulative = NativeHomeActivity.totalTokens(total);

        LinearLayout box = Ui.col((Context)this);
        box.setPadding(Ui.dp((Context)this, 18), Ui.dp((Context)this, 4), Ui.dp((Context)this, 18), Ui.dp((Context)this, 12));

        /* 一、上下文窗口：只表示当前上下文占用。 */
        box.addView(this.section("上下文窗口"));
        LinearLayout hero = Ui.row((Context)this);
        hero.setGravity(16);
        hero.setBackground(Ui.round(Ui.card, Ui.dp((Context)this, 18)));
        Ui.pad(hero, 16, 16, 16, 16);
        ContextRing ring = new ContextRing((Context)this);
        ring.setProgress(window > 0 ? (float)used / (float)window : 0.0f, tint);
        hero.addView((View)ring, Ui.w(Ui.dp((Context)this, 46), Ui.dp((Context)this, 46)));
        LinearLayout numberBox = Ui.col((Context)this);
        LinearLayout.LayoutParams numberSpace = new LinearLayout.LayoutParams(0, -2, 1.0f);
        numberSpace.leftMargin = Ui.dp((Context)this, 14);
        hero.addView((View)numberBox, numberSpace);
        numberBox.addView(Ui.text((Context)this, NativeHomeActivity.tokenText(used) + (window > 0 ? " / " + NativeHomeActivity.tokenText(window) : ""), 19.0f, Ui.text, Ui.medium((Context)this)));
        numberBox.addView(Ui.text((Context)this, window > 0 ? "已用 " + percent + "% · 剩余 " + NativeHomeActivity.tokenText(Math.max(0, window - used)) : "当前上下文占用", 12.0f, Ui.muted, null));
        box.addView((View)hero, Ui.fill());
        if (this.usage.optBoolean("contextStale", false)) {
            TextView note = Ui.text((Context)this, "上下文已压缩，占用按压缩后的窗口计算。", 12.0f, Ui.warn, null);
            note.setLineSpacing(Ui.dp((Context)this, 3), 1.0f);
            LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
            noteSpace.topMargin = Ui.dp((Context)this, 10);
            box.addView((View)note, noteSpace);
        }

        /* 二、本轮 token：只表示最近一轮的输入与输出。 */
        box.addView(this.section("本轮 token"));
        int input = last == null ? 0 : (int)last.optDouble("inputTokens", 0.0);
        int output = last == null ? 0 : (int)last.optDouble("outputTokens", 0.0);
        if (input > 0 || output > 0) {
            box.addView((View)this.statPair("输入", input, "输出", output), Ui.fill());
            box.addView((View)this.statPair("推理", last == null ? 0 : (int)last.optDouble("reasoningOutputTokens", 0.0), "缓存读取", last == null ? 0 : (int)last.optDouble("cachedInputTokens", 0.0)), Ui.fill());
        } else {
            TextView missing = Ui.text((Context)this, "该引擎尚未返回本轮明细。", 12.0f, Ui.faint, null);
            missing.setPadding(0, Ui.dp((Context)this, 4), 0, 0);
            box.addView((View)missing);
        }

        /* 三、会话统计：轮次、消息与累计 token，和上下文占用分开。 */
        box.addView(this.section("会话统计"));
        LinearLayout sum = Ui.row((Context)this);
        sum.addView(this.statCell("轮次", counts[0]));
        sum.addView(this.statCell("消息", counts[1]));
        sum.addView(this.statCell("累计 token", cumulative));
        box.addView((View)sum, Ui.fill());
        if (cumulative <= 0) {
            TextView note = Ui.text((Context)this, "当前引擎协议只返回上下文占用，未提供累计 token；这里不估算、不混用上下文占用。", 12.0f, Ui.faint, null);
            note.setLineSpacing(Ui.dp((Context)this, 3), 1.0f);
            LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
            noteSpace.topMargin = Ui.dp((Context)this, 10);
            box.addView((View)note, noteSpace);
        }

        BottomSheetDialog sheet = new BottomSheetDialog((Context)this);
        LinearLayout content = Ui.col((Context)this);
        content.setPadding(0, Ui.dp((Context)this, 6), 0, Ui.dp((Context)this, 6));
        TextView title = Ui.text((Context)this, "运行统计", 17.0f, Ui.text, Ui.medium((Context)this));
        title.setPadding(Ui.dp((Context)this, 18), Ui.dp((Context)this, 6), Ui.dp((Context)this, 18), Ui.dp((Context)this, 4));
        content.addView(title);
        content.addView((View)box, Ui.fill());
        ScrollView scroll = new ScrollView((Context)this);
        scroll.addView(content);
        sheet.setContentView((View)scroll);
        sheet.show();
    }

    private TextView section(String label) {
        TextView view = Ui.text((Context)this, label, 12.0f, Ui.faint, Ui.medium((Context)this));
        view.setPadding(0, Ui.dp((Context)this, 14), 0, Ui.dp((Context)this, 7));
        return view;
    }

    private LinearLayout statPair(String leftLabel, int left, String rightLabel, int right) {
        LinearLayout row = Ui.row((Context)this);
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.topMargin = Ui.dp((Context)this, 8);
        row.setLayoutParams(space);
        row.addView(this.statCell(leftLabel, left));
        row.addView(this.statCell(rightLabel, right));
        return row;
    }

    private LinearLayout statCell(String label, int value) {
        LinearLayout cell = Ui.col((Context)this);
        cell.setBackground(Ui.round(Ui.card, Ui.dp((Context)this, 14)));
        Ui.pad(cell, 14, 11, 14, 11);
        cell.addView(Ui.text((Context)this, label, 11.5f, Ui.faint, null));
        cell.addView(Ui.text((Context)this, NativeHomeActivity.tokenText(value), 16.0f, Ui.text, Ui.medium((Context)this)));
        return cell;
    }

    private LinearLayout heroRow(TextView value, String label) {
        LinearLayout row = Ui.row((Context)this);
        row.setGravity(16);
        Ui.pad(row, 14, 12, 14, 12);
        row.addView(value);
        TextView caption = Ui.text((Context)this, label, 12.0f, Ui.muted, null);
        LinearLayout.LayoutParams captionSpace = new LinearLayout.LayoutParams(-2, -2);
        captionSpace.leftMargin = Ui.dp((Context)this, 8);
        row.addView((View)caption, captionSpace);
        return row;
    }

    private LinearLayout tokenRow(String label, int value) {
        LinearLayout row = Ui.row((Context)this);
        row.setGravity(16);
        Ui.pad(row, 14, 11, 14, 11);
        row.addView((View)Ui.text((Context)this, label, 13.5f, Ui.muted, null), new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(Ui.text((Context)this, value <= 0 ? "\u2014" : NativeHomeActivity.tokenText(value), 13.5f, Ui.text, Ui.medium((Context)this)));
        return row;
    }

    private LinearLayout.LayoutParams withMargin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = left;
        params.topMargin = top;
        params.rightMargin = right;
        params.bottomMargin = bottom;
        return params;
    }

    private String contextLabel() {
        JSONObject value = this.usage;
        if (value == null) {
            return "";
        }
        JSONObject tokens = value.optJSONObject("tokenUsage");
        if (tokens == null) {
            return "";
        }
        JSONObject last = tokens.optJSONObject("last");
        int used = NativeHomeActivity.contextTokens(last);
        int window = this.contextWindow();
        if (used <= 0) {
            return "";
        }
        String label = "\u4e0a\u4e0b\u6587 " + NativeHomeActivity.tokenText(used) + (window > 0 ? " / " + NativeHomeActivity.tokenText(window) : "");
        if (value.optBoolean("contextStale", false)) {
            label = label + " \u00b7 \u5df2\u538b\u7f29";
        }
        return label;
    }

    private static String tokenText(int value) {
        if (value >= 1000000) {
            return String.format(Locale.ROOT, "%.1fM", Float.valueOf((float)value / 1000000.0f));
        }
        if (value >= 1000) {
            return String.format(Locale.ROOT, "%.1fk", Float.valueOf((float)value / 1000.0f));
        }
        return String.valueOf(value);
    }

    private void refreshUsage() {
        this.refreshUsage("");
    }

    private void refreshUsage(String finishedTurn) {
        if (this.threadId.isEmpty() || !NativeEngine.ready()) {
            this.usage = null;
            return;
        }
        String owner = this.threadId;
        this.quiet("native/chat/usage/read", NativeUi.json("threadId", owner), r -> {
            if (!owner.equals(this.threadId)) {
                return;
            }
            this.usage = r;
            if (this.thread != null) try { this.thread.put("usage", r); } catch (JSONException ignored) {}
            this.updateStatusLine();
            this.updateUsageRow();
            if (!finishedTurn.isEmpty()) {
                JSONObject tokens = r.optJSONObject("tokenUsage");
                JSONObject last = tokens == null ? null : tokens.optJSONObject("last");
                int input = last == null ? 0 : (int)last.optDouble("inputTokens", 0.0);
                int output = last == null ? 0 : (int)last.optDouble("outputTokens", 0.0);
                int used = input + output;
                if (used > 0) {
                    this.rememberTurn(finishedTurn, 0L, used);
                    this.renderMessages(false);
                }
            }
        });
    }

    private void updateSubtitle() {
        if (this.topSubtitle == null) {
            return;
        }
        String name = NativeHomeActivity.folder(this.cwd);
        this.topSubtitle.setText(this.collaboration!=null&&this.collaboration.enabled()?name+" · "+this.collaboration.members()+" 位协作成员":this.model.isEmpty()?name:name+" · "+NativeHomeActivity.shortModel(this.model));
    }

    private void updateControls() {
        if (this.sendButton == null) {
            return;
        }
        this.sendButton.setVisibility(View.VISIBLE);
        this.updateExecutionButton();
        if(this.messageQueue!=null)this.messageQueue.render();
        this.updateAttach();
        this.updateCollaborationChrome();
        if (this.effortLabel != null) {
            this.effortLabel.setText(this.collaboration!=null&&this.collaboration.enabled()?"成员配置":this.effortName());
        }
        this.updateUsageRow();
        if (this.turnId.isEmpty()) {
            this.statusIdle();
        } else {
            this.updateStatusLine();
        }
    }

    /** 把会话内的沙箱路径映射回宿主文件，缩略图才有东西可解码。 */
    private File uploadFile(String path) {
        if (path == null || path.isEmpty()) return null;
        if (this.thumbRuntime == null) this.thumbRuntime = new RuntimeManager((Context)this, s -> {});
        return new File(this.thumbRuntime.root, path.startsWith("/") ? path.substring(1) : path);
    }

    /** 后台解码并回填缩略图；标签不符说明格子已被复用，直接丢弃。 */
    private void loadThumb(ImageView view, String path, int size) {
        Bitmap cached = this.thumbCache.get(path);
        if (cached != null && !cached.isRecycled()) {
            view.setColorFilter(null);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setImageBitmap(cached);
            return;
        }
        view.setTag(path);
        File file = this.uploadFile(path);
        if (file == null || !file.isFile()) return;
        CompletableFuture.runAsync(() -> {
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    int sample = 1;
                    while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2;
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sample;
                    bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                }
            } catch (Exception ignored) {
            }
            final Bitmap loaded = bitmap;
            if (loaded == null) return;
            this.thumbCache.put(path, loaded);
            this.runOnUiThread(() -> {
                if (this.isDestroyed() || !path.equals(view.getTag())) return;
                view.setColorFilter(null);
                view.setScaleType(ImageView.ScaleType.CENTER_CROP);
                view.setImageBitmap(loaded);
            });
        });
    }

    /** 附件查看：全屏黑底、双指缩放、✕ 关闭。 */
    private void previewAttachment(String path, String name) {
        final File file = this.uploadFile(path);
        if (file == null || !file.isFile()) {
            NativeUi.toast((Activity)this, "\u56fe\u7247\u6587\u4ef6\u5df2\u4e0d\u5b58\u5728");
            return;
        }
        final ViewGroup host = (ViewGroup)this.findViewById(android.R.id.content);
        if (host == null) return;
        final FrameLayout root = new FrameLayout((Context)this);
        root.setBackgroundColor(Color.BLACK);
        root.setClickable(true);
        final ImageView photo = new ImageView((Context)this);
        photo.setScaleType(ImageView.ScaleType.MATRIX);
        photo.setContentDescription(name == null ? "" : name);
        root.addView((View)photo, new FrameLayout.LayoutParams(-1, -1));
        TextView close = Ui.text((Context)this, "\u00d7", 26.0f, Color.WHITE, null);
        Ui.pad(close, Ui.dp((Context)this, 16), Ui.dp((Context)this, 8), Ui.dp((Context)this, 16), Ui.dp((Context)this, 8));
        FrameLayout.LayoutParams closeSpace = new FrameLayout.LayoutParams(-2, -2);
        closeSpace.gravity = Gravity.TOP | Gravity.END;
        closeSpace.topMargin = Ui.dp((Context)this, 28);
        closeSpace.rightMargin = Ui.dp((Context)this, 8);
        root.addView((View)close, closeSpace);
        Ui.touchable(close);
        final Runnable dismiss = () -> host.removeView((View)root);
        close.setOnClickListener(v -> dismiss.run());
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, code, event) -> {
            if (code == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                dismiss.run();
                return true;
            }
            return false;
        });
        final Matrix matrix = new Matrix();
        final ScaleGestureDetector detector = new ScaleGestureDetector((Context)this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector source) {
                        float factor = source.getScaleFactor();
                        matrix.postScale(factor, factor, source.getFocusX(), source.getFocusY());
                        photo.setImageMatrix(matrix);
                        return true;
                    }
                });
        photo.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return true;
        });
        host.addView((View)root, new FrameLayout.LayoutParams(-1, -1));
        root.requestFocus();
        root.post(() -> CompletableFuture.runAsync(() -> {
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    int sample = 1;
                    final int target = Math.max(root.getWidth(), root.getHeight());
                    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2;
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sample;
                    bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                }
            } catch (Exception ignored) {
            }
            final Bitmap loaded = bitmap;
            this.runOnUiThread(() -> {
                if (loaded == null) {
                    NativeUi.toast((Activity)this, "\u65e0\u6cd5\u6253\u5f00\u8fd9\u5f20\u56fe\u7247");
                    return;
                }
                photo.setImageBitmap(loaded);
                float width = Math.max(1, root.getWidth());
                float height = Math.max(1, root.getHeight());
                float scale = Math.min(width / loaded.getWidth(), height / loaded.getHeight());
                matrix.setScale(scale, scale);
                matrix.postTranslate((width - loaded.getWidth() * scale) / 2f, (height - loaded.getHeight() * scale) / 2f);
                photo.setImageMatrix(matrix);
            });
        }));
    }

    /** 双击引用条回到原文：在已渲染的消息里找第一处包含该段文字的文本并滚过去。 */
    void revealQuoted(String text) {
        String needle = text == null ? "" : text.trim();
        while (needle.endsWith("\n")) needle = needle.substring(0, needle.length() - 1).trim();
        if (needle.isEmpty() || this.messages == null) return;
        TextView target = NativeHomeActivity.quoteSource(this.messages, needle);
        if (target == null && needle.contains("\n")) {
            target = NativeHomeActivity.quoteSource(this.messages, needle.substring(0, needle.indexOf('\n')).trim());
        }
        if (target == null) {
            NativeUi.toast((Activity)this, "\u539f\u6d88\u606f\u4e0d\u5728\u5f53\u524d\u4f1a\u8bdd\u4e2d");
            return;
        }
        int y = 0;
        View node = (View)target;
        while (node != null && node != this.messages) {
            y += node.getTop();
            ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
        }
        this.chat.smoothScrollTo(0, Math.max(0, y - Ui.dp((Context)this, 96)));
        target.setAlpha(0.35f);
        target.animate().alpha(1f).setDuration(420).start();
    }

    /** 跳过引用卡自身，否则双击会停在引用卡而不是原文。 */
    private static TextView quoteSource(ViewGroup group, String needle) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if ("quote".equals(child.getTag())) continue;
            if (child instanceof TextView) {
                CharSequence value = ((TextView) child).getText();
                if (value != null && value.toString().contains(needle)) return (TextView) child;
            } else if (child instanceof ViewGroup) {
                TextView found = NativeHomeActivity.quoteSource((ViewGroup) child, needle);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 取消排队后把完整输入放回输入区，文字与图片附件都保留。 */
    private void restoreQueuedInput(JSONArray input) {
        if (this.composer == null) return;
        boolean changed = this.composerContent();
        JSONArray existing = new JSONArray();
        for (JSONObject image : this.attachments) existing.put(image);
        try {
            input = ConversationContent.restoredInput(input, this.composer.getText().toString(), existing);
        } catch (JSONException error) {
            NativeUi.error(this, "恢复待发消息失败：" + error.getMessage());
            return;
        }
        StringBuilder text = new StringBuilder();
        ArrayList<JSONObject> images = new ArrayList<>();
        for (int i = 0; input != null && i < input.length(); i++) {
            JSONObject part = input.optJSONObject(i);
            if (part == null) continue;
            if ("text".equals(part.optString("type"))) {
                if (text.length() > 0) text.append('\n');
                text.append(part.optString("text"));
            } else {
                images.add(part);
            }
        }
        this.composer.setText(text.toString());
        this.composer.setSelection(text.length());
        this.attachments.clear();
        this.attachments.addAll(images);
        this.refreshQuote();
        this.updateAttach();
        if (changed) NativeUi.toast(this, "编辑期间新增的草稿和附件已保留");
        this.composer.requestFocus();
        this.composer.post(() -> {
            InputMethodManager keyboard = (InputMethodManager) this.getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(this.composer, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void updateAttach() {
        this.updateExecutionButton();
        if (this.attachButton == null) {
            return;
        }
        this.attachButton.setContentDescription(this.attachments.isEmpty() ? "\u6dfb\u52a0\u56fe\u7247" : "\u5df2\u6dfb\u52a0 " + this.attachments.size() + " \u5f20\u56fe\u7247");
        ImageView icon = (ImageView)this.attachButton.getChildAt(0);
        icon.setColorFilter(this.attachments.isEmpty() ? Ui.muted : Ui.accent);
        boolean images = this.collaboration != null && this.collaboration.enabled() || this.modelSupportsImages(this.model);
        this.attachButton.setAlpha(images ? 1.0f : 0.35f);
        if (this.attachStrip == null) {
            return;
        }
        this.attachStrip.removeAllViews();
        boolean empty = this.attachments.isEmpty();
        if (this.attachScroll != null) this.attachScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        this.attachStrip.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            return;
        }
        for (int i = 0; i < this.attachments.size(); i++) {
            JSONObject item = this.attachments.get(i);
            String path = item.optString("path", "");
            String name = item.optString("name", "");
            if (name.isEmpty()) name = path.substring(path.lastIndexOf('/') + 1);
            if (name.isEmpty()) name = "\u56fe\u7247";
            LinearLayout chip = Ui.row((Context)this);
            chip.setGravity(16);
            chip.setBackground(Ui.round(Ui.cardHigh, Ui.dp((Context)this, 14)));
            Ui.pad(chip, 6, 6, 6, 6);
            LinearLayout thumb = Ui.col((Context)this);
            thumb.setGravity(17);
            thumb.setBackground(Ui.round(Ui.accentSoft, Ui.dp((Context)this, 10)));
            ImageView glyph = new ImageView((Context)this);
            glyph.setImageResource(R.drawable.ic_attachment);
            glyph.setColorFilter(Ui.accent);
            glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumb.setClipToOutline(true);
            thumb.addView((View)glyph, new LinearLayout.LayoutParams(-1, -1));
            this.loadThumb(glyph, path, Ui.dp((Context)this, 40));
            chip.addView((View)thumb, Ui.w(Ui.dp((Context)this, 30), Ui.dp((Context)this, 30)));
            LinearLayout labels = Ui.col((Context)this);
            LinearLayout.LayoutParams labelsSpace = new LinearLayout.LayoutParams(-2, -2);
            labelsSpace.leftMargin = Ui.dp((Context)this, 8);
            chip.addView((View)labels, labelsSpace);
            TextView label = Ui.text((Context)this, name, 11.5f, Ui.text, Ui.medium((Context)this));
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            label.setMaxWidth(Ui.dp((Context)this, 132));
            labels.addView((View)label);
            TextView meta = Ui.text((Context)this, "\u56fe\u7247", 10.0f, Ui.faint, null);
            labels.addView((View)meta);
            TextView remove = Ui.text((Context)this, "\u00d7", 15.0f, Ui.faint, null);
            Ui.pad(remove, 8, 0, 4, 0);
            final int index = i;
            final String title = name;
            Ui.touchable(chip);
            chip.setOnClickListener(v -> {
                if (index >= this.attachments.size()) return;
                this.previewAttachment(this.attachments.get(index).optString("path", ""), title);
            });
            Ui.touchable(remove);
            remove.setOnClickListener(v -> {
                if (index < this.attachments.size()) {
                    this.attachments.remove(index);
                    this.updateAttach();
                }
            });
            chip.addView((View)remove);
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
            space.rightMargin = Ui.dp((Context)this, 8);
            space.topMargin = Ui.dp((Context)this, 2);
            this.attachStrip.addView((View)chip, space);
        }
        if (this.attachments.size() > 1) {
            TextView clear = Ui.text((Context)this, "\u6e05\u7a7a", 11.5f, Ui.accent, Ui.medium((Context)this));
            Ui.pad(clear, 6, 4, 6, 4);
            Ui.touchable(clear);
            clear.setOnClickListener(v -> {
                this.attachments.clear();
                this.updateAttach();
            });
            this.attachStrip.addView((View)clear);
        }
    }

    private String effortName() {
        return NativeHomeActivity.effortTitle(this.effort);
    }

    private static String effortTitle(String value) {
        if ("minimal".equals(value)) return "\u6781\u7b80";
        if ("low".equals(value)) return "\u8f7b\u91cf";
        if ("medium".equals(value)) return "\u5747\u8861";
        if ("xhigh".equals(value)) return "\u6781\u9ad8";
        if ("max".equals(value)) return "MAX";
        if ("ultra".equals(value)) return "ULTRA";
        if ("high".equals(value)) return "\u6df1\u5165";
        if ("none".equals(value) || "off".equals(value)) return "关闭";
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private static String effortNote(String value) {
        if ("minimal".equals(value)) return "\u6700\u5c11\u63a8\u7406\uff0c\u6700\u5feb\u54cd\u5e94";
        if ("low".equals(value)) return "\u5feb\u901f\u56de\u5e94\uff0c\u5c11\u505a\u63a8\u7406";
        if ("medium".equals(value)) return "\u65e5\u5e38\u4efb\u52a1\uff0c\u901f\u5ea6\u4e0e\u8d28\u91cf\u5e73\u8861";
        if ("xhigh".equals(value)) return "\u66f4\u957f\u63a8\u7406\uff0c\u9002\u5408\u96be\u9898";
        if ("max".equals(value)) return "\u56f0\u96be\u95ee\u9898\uff0c\u5c3d\u53ef\u80fd\u6df1\u5165";
        if ("ultra".equals(value)) return "最高推理档位，可自动委派任务";
        return "\u590d\u6742\u6539\u52a8\uff0c\u5141\u8bb8\u66f4\u957f\u601d\u8003";
    }

    private ArrayList<String> supportedEfforts(String id2) {
        ArrayList<String> fallback = new ArrayList<>();
        fallback.add("low");
        fallback.add("medium");
        fallback.add("high");
        if (id2 == null || id2.isEmpty()) return fallback;
        JSONObject info = this.modelMeta.get(id2);
        if (info == null || !info.has("supportedReasoningEfforts")) return fallback;
        JSONArray source = info.optJSONArray("supportedReasoningEfforts");
        if (source == null) return fallback;
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < source.length(); ++i) {
            Object item = source.opt(i);
            String value = item instanceof String ? (String)item
                : item instanceof JSONObject ? ((JSONObject)item).optString("reasoningEffort",
                      ((JSONObject)item).optString("effort", "")) : "";
            if (!value.isEmpty() && !values.contains(value)) values.add(value);
        }
        return values;
    }

    private boolean modelSupportsImages(String id2) {
        JSONObject info = this.modelMeta.get(id2);
        if (info == null) return true;
        JSONArray modalities = info.optJSONArray("inputModalities");
        if (modalities == null) return true;
        for (int i = 0; i < modalities.length(); ++i)
            if ("image".equals(modalities.optString(i))) return true;
        return false;
    }

    private String modelCapabilityNote(String id2) {
        JSONObject info = this.modelMeta.get(id2);
        ArrayList<String> caps = new ArrayList<>();
        if (this.modelSupportsImages(id2)) caps.add("\u56fe\u7247");
        ArrayList<String> efforts = this.supportedEfforts(id2);
        if (!efforts.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String value : efforts) {
                if (names.length() > 0) names.append('/');
                names.append(NativeHomeActivity.effortTitle(value));
            }
            caps.add("\u601d\u8003 " + names);
        }
        if (info != null) {
            int window = info.optInt("contextWindow", 0);
            if (window > 0) caps.add(Math.round(window / 1000f) + "k");
        }
        String details = String.join(" \u00b7 ", caps);
        return details.isEmpty() ? id2 : details + "\n" + id2;
    }

    private void normalizeEffort() {
        ArrayList<String> supported = this.supportedEfforts(this.model);
        if (!supported.isEmpty() && !supported.contains(this.effort)) {
            String fallback = "";
            JSONObject info = this.modelMeta.get(this.model);
            if (info != null) fallback = info.optString("defaultReasoningEffort", "");
            this.effort = supported.contains(fallback) ? fallback : supported.get(0);
        }
        if (this.effortLabel != null) this.effortLabel.setText(this.effortName());
    }

    private void updateModelCapabilities() {
        if (this.attachButton != null) {
            boolean images = this.collaboration!=null&&this.collaboration.enabled()||this.modelSupportsImages(this.model);
            this.attachButton.setEnabled(images);
            this.attachButton.setAlpha(images ? 1.0f : 0.35f);
        }
        if (this.effortLabel != null) this.effortLabel.setText(this.effortName());
        if (this.effortPill != null)
            this.effortPill.setVisibility(this.supportedEfforts(this.model).isEmpty() ? View.GONE : View.VISIBLE);
    }

    static String shortModel(String value) {
        if (value == null || value.isEmpty()) {
            return "\u9009\u62e9\u6a21\u578b";
        }
        String trimmed = value;
        int slash = trimmed.lastIndexOf(47);
        if (slash >= 0 && slash < trimmed.length() - 1) {
            trimmed = trimmed.substring(slash + 1);
        }
        return trimmed.length() > 26 ? trimmed.substring(0, 26) + "\u2026" : trimmed;
    }

    static String join(String separator, String ... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(part);
        }
        return out.toString();
    }

    private JSONObject turnStats() {
        if (this.turnStats == null) {
            try {
                this.turnStats = new JSONObject(this.getSharedPreferences("native-chat", 0).getString("turn-stats", "{}"));
            }
            catch (Exception e) {
                this.turnStats = new JSONObject();
            }
        }
        return this.turnStats;
    }

    private void rememberTurn(String id2, long millis, int tokens) {
        if (id2.isEmpty()) {
            return;
        }
        JSONObject all = this.turnStats();
        try {
            JSONObject entry = all.optJSONObject(id2);
            if (entry == null) {
                entry = new JSONObject();
            }
            if (millis > 0L) {
                entry.put("ms", millis);
            }
            if (tokens > 0) {
                entry.put("tokens", tokens);
            }
            all.put(id2, entry);
            if (all.length() > 120) {
                Iterator<String> keys = all.keys();
                while (all.length() > 120 && keys.hasNext()) {
                    String key = keys.next();
                    if (key.equals(id2)) continue;
                    all.remove(key);
                }
            }
            this.getSharedPreferences("native-chat", 0).edit().putString("turn-stats", all.toString()).apply();
        }
        catch (JSONException jSONException) {
            // empty catch block
        }
    }

    private String turnFooter(String turnId) {
        JSONObject entry = this.turnStats().optJSONObject(turnId);
        StringBuilder out = new StringBuilder();
        long tokens = entry == null ? 0L : entry.optLong("tokens", 0L);
        long millis = ConversationContent.durationMillis(this.currentTurn(turnId), entry == null ? 0L : entry.optLong("ms", 0L));
        if (tokens > 0L) {
            out.append(NativeHomeActivity.tokenText((int)tokens)).append(" tokens");
        }
        if (millis > 0L) {
            if (out.length() > 0) {
                out.append(" \u00b7 ");
            }
            out.append(NativeHomeActivity.elapsedText(millis));
        }
        return out.toString();
    }

    private String versionName() {
        try {
            return this.getPackageManager().getPackageInfo((String)this.getPackageName(), (int)0).versionName;
        }
        catch (Exception ignored) {
            return "";
        }
    }

    private static String dayLabel(String stamp) {
        LocalDate today;
        long millis = ConversationContent.stampMillis(stamp);
        if (millis <= 0L) {
            return "\u6700\u8fd1";
        }
        LocalDate date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
        if (date.equals(today = LocalDate.now())) {
            return "\u4eca\u5929";
        }
        if (date.equals(today.minusDays(1L))) {
            return "\u6628\u5929";
        }
        if (date.isAfter(today.minusDays(7L))) {
            return "\u672c\u5468";
        }
        return "\u66f4\u65e9";
    }

    private ArrayList<JSONObject> sortedThreads() {
        ArrayList<JSONObject> ordered = new ArrayList<JSONObject>(this.drawerThreads);
        ordered.sort((left, right) -> Long.compare(ConversationContent.stampMillis(ConversationContent.stamp(right, "updatedAt", "createdAt")), ConversationContent.stampMillis(ConversationContent.stamp(left, "updatedAt", "createdAt"))));
        return ordered;
    }

    static String folder(String path) {
        if (path == null || path.isEmpty()) {
            return "projects";
        }
        int at = path.lastIndexOf(47);
        String name = path.substring(at + 1);
        return name.isEmpty() ? path : name;
    }

    private void renderQuestionCard(JSONObject request, String approvalId) {
        if (this.askHost == null) {
            return;
        }
        JSONObject rawParams = request.optJSONObject("params");
        JSONObject params = rawParams == null ? new JSONObject() : rawParams;
        JSONArray questions = params.optJSONArray("questions");
        if (questions == null || questions.length() == 0) {
            this.answerApproval(approvalId, NativeUi.json("answers", new JSONObject()), () -> {});
            return;
        }
        this.renderQuestion(request, approvalId, new Questionnaire(questions), 0);
    }

    private void renderQuestion(JSONObject request, String approvalId, Questionnaire form, int index) {
        String cardId = approvalId + "-question-" + index;
        this.renderAsk(form.payload(index, cardId), choice -> {
            form.save(index, choice);
            if (choice.optBoolean("previous")) {
                this.removeAsk(cardId);
                this.renderQuestion(request, approvalId, form, index - 1);
            } else if (index + 1 < form.questions.length()) {
                this.removeAsk(cardId);
                this.renderQuestion(request, approvalId, form, index + 1);
            } else {
                if (request == null) {
                    JSONObject result = form.result("");
                    try { result.put("answered", true); } catch (JSONException ignored) {}
                    if (!AskQueue.answer(approvalId, result)) NativeUi.toast(this, "这个问题已结束");
                    this.removeAsk(cardId);
                } else {
                    JSONObject params = request.optJSONObject("params");
                    this.answerApproval(approvalId, form.result(request.optString("engine",params == null ? this.engine : params.optString("engine", this.engine))), () -> this.removeAsk(cardId));
                }
            }
        });
    }

    private void removeAsk(String id) {
        if (this.askHost == null) return;
        for (int i = this.askHost.getChildCount() - 1; i >= 0; i--) {
            View child = this.askHost.getChildAt(i);
            Object tag = child.getTag();
            if (id.equals(tag) || tag instanceof String && ((String)tag).startsWith(id + "-question-")) this.askHost.removeView(child);
        }
    }

    private final HashSet<String> answeringApprovals = new HashSet<>();
    private void answerApproval(String approvalId, JSONObject result, Runnable success) {
        if (!this.answeringApprovals.add(approvalId)) return;
        JSONObject collaborationRequest=this.collaborationApprovals.get(approvalId);
        JSONObject approvalParams=NativeUi.json("id",approvalId,"result",result);
        if(collaborationRequest!=null)try{approvalParams.put("workflowId",collaborationRequest.optString("workflowId")).put("taskId",collaborationRequest.optString("taskId")).put("attemptId",collaborationRequest.optString("attemptId"));}catch(Exception ignored){}
        NativeEngine.call(collaborationRequest==null?"approvals/respond":"native/collaboration/respond", approvalParams, r -> {
            this.collaborationApprovals.remove(approvalId);
            this.answeringApprovals.remove(approvalId);
            this.approvals.remove(approvalId);
            success.run();
        }, m -> {
            this.answeringApprovals.remove(approvalId);
            NativeUi.error(this, m);
        });
    }

    private void renderAsk(JSONObject ask) {
        JSONObject execution=ask.optJSONObject("execution");
        if(execution!=null&&!this.collaboration.owns(execution.optString("workflowId")))return;
        if (!ask.optBoolean("resolved") && ask.optJSONArray("questions") != null) {
            this.renderQuestion(null, ask.optString("id"), new Questionnaire(ask.optJSONArray("questions")), 0);
        } else this.renderAsk(ask, null);
    }

    private void renderAsk(JSONObject ask, Consumer<JSONObject> done) {
        if (this.askHost == null) {
            return;
        }
        String id2 = ask.optString("id");
        if (ask.optBoolean("resolved")) { this.removeAsk(id2); return; }
        for (int i = 0; i < this.askHost.getChildCount(); ++i) {
            if (!id2.equals(this.askHost.getChildAt(i).getTag())) continue;
            return;
        }
        MaterialCardView panel = new MaterialCardView(this);
        panel.setTag(id2);
        panel.setRadius(Ui.dp(this, 16));
        panel.setCardBackgroundColor(Ui.card);
        panel.setStrokeWidth(Ui.dp(this, 1));
        panel.setStrokeColor(androidx.core.graphics.ColorUtils.blendARGB(Ui.line, Ui.accent, 0.25f));
        panel.setCardElevation(Ui.dp(this, 6));
        panel.setUseCompatPadding(false);
        panel.setPreventCornerOverlap(false);
        LinearLayout card = Ui.col((Context)this);
        Ui.pad(card, 12, 10, 12, 10);
        panel.addView(card, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout head = Ui.row((Context)this);
        head.setGravity(16);
        ImageView dot = new ImageView((Context)this);
        dot.setImageResource(R.drawable.ic_spark);
        dot.setColorFilter(Ui.onAccentSoft);
        head.addView((View)dot, Ui.w(Ui.dp((Context)this, 15), Ui.dp((Context)this, 15)));
        TextView who = Ui.text((Context)this, NativeUi.engineName(this.engine) + " · " + ask.optString("step", ask.optBoolean("multiSelect") ? "多选" : "单选"), 10.5f, Ui.onAccentSoft, Ui.medium((Context)this));
        LinearLayout.LayoutParams whoSpace = new LinearLayout.LayoutParams(0, -2, 1.0f);
        whoSpace.leftMargin = Ui.dp((Context)this, 6);
        head.addView((View)who, whoSpace);
        card.addView(head);
        TextView question = Ui.text((Context)this, ask.optString("question"), 13.0f, Ui.text, Ui.medium((Context)this));
        question.setLineSpacing(Ui.dp((Context)this, 3), 1.0f);
        LinearLayout.LayoutParams questionSpace = new LinearLayout.LayoutParams(-1, -2);
        questionSpace.topMargin = Ui.dp((Context)this, 4);
        card.addView((View)question, questionSpace);
        JSONArray options = ask.optJSONArray("options");
        boolean multi = ask.optBoolean("multiSelect", false);
        ArrayList<String> selected = new ArrayList<>();
        JSONArray initial = ask.optJSONArray("selected");
        if (initial != null) for (int i = 0; i < initial.length(); i++) selected.add(initial.optString(i));
        ArrayList<LinearLayout> rows = new ArrayList<LinearLayout>();
        CappedScrollView optionScroll = new CappedScrollView(this);
        LinearLayout optionList = Ui.col((Context)this);
        optionScroll.addView((View)optionList, new FrameLayout.LayoutParams(-1, -2));
        card.addView((View)optionScroll, new LinearLayout.LayoutParams(-1, -2));
        if (options != null) {
            for (int i = 0; i < options.length(); ++i) {
                JSONObject option = options.optJSONObject(i);
                if (option == null) continue;
                String label = option.optString("label");
                LinearLayout row = Ui.row((Context)this);
                row.setGravity(16);
                row.setBackground(Ui.round((Context)this, Ui.cardHigh, 10.0f, 1.0f, Ui.lineSoft));
                Ui.pad(row, 9, 6, 9, 6);
                row.setMinimumHeight(Ui.dp(this, 36));
                LinearLayout.LayoutParams rowSpace = new LinearLayout.LayoutParams(-1, -2);
                rowSpace.topMargin = Ui.dp((Context)this, 4);
                optionList.addView((View)row, rowSpace);
                ImageView mark = new ImageView((Context)this);
                mark.setImageResource(R.drawable.ic_check);
                mark.setColorFilter(Ui.faint);
                mark.setAlpha(0.35f);
                row.addView((View)mark, Ui.w(Ui.dp((Context)this, 15), Ui.dp((Context)this, 15)));
                LinearLayout labels = Ui.col((Context)this);
                LinearLayout.LayoutParams labelSpace = new LinearLayout.LayoutParams(0, -2, 1.0f);
                labelSpace.leftMargin = Ui.dp((Context)this, 9);
                row.addView((View)labels, labelSpace);
                labels.addView(Ui.text((Context)this, label, 12.5f, Ui.text, Ui.medium((Context)this)));
                String detail = option.optString("description");
                if (!detail.isEmpty()) {
                    TextView note = Ui.text((Context)this, detail, 10.5f, Ui.muted, null);
                    note.setMaxLines(2);
                    note.setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
                    noteSpace.topMargin = Ui.dp((Context)this, 2);
                    labels.addView((View)note, noteSpace);
                }
                if (selected.contains(label)) {
                    mark.setAlpha(1f); mark.setColorFilter(Ui.accent);
                    row.setBackground(Ui.round(this, Ui.accentSoft, 10f, 1f, Ui.accent));
                }
                rows.add(row);
                int index = rows.size() - 1;
                row.setOnClickListener(v -> {
                    boolean on;
                    boolean bl = on = !selected.contains(label);
                    if (!multi) {
                        selected.clear();
                        for (int r = 0; r < rows.size(); ++r) {
                            ImageView icon = (ImageView)((LinearLayout)rows.get(r)).getChildAt(0);
                            icon.setAlpha(0.35f);
                            icon.setColorFilter(Ui.faint);
                            ((LinearLayout)rows.get(r)).setBackground(Ui.round((Context)this, Ui.cardHigh, 10.0f, 1.0f, Ui.lineSoft));
                        }
                    }
                    if (on) {
                        selected.add(label);
                    } else {
                        selected.remove(label);
                    }
                    ImageView icon = (ImageView)((LinearLayout)rows.get(index)).getChildAt(0);
                    icon.setAlpha(on ? 1.0f : 0.35f);
                    icon.setColorFilter(on ? Ui.accent : Ui.faint);
                    row.setBackground(Ui.round((Context)this, on ? Ui.accentSoft : Ui.cardHigh, 10.0f, 1.0f, on ? Ui.accent : Ui.lineSoft));
                });
            }
        }
        EditText extra = null;
        if (ask.optBoolean("allowText", true)) {
            extra = new EditText((Context)this);
            extra.setHint("\u8865\u5145\u8bf4\u660e\uff08\u53ef\u9009\uff09");
            extra.setHintTextColor(Ui.faint);
            extra.setTextColor(Ui.text);
            extra.setTextSize(12.0f);
            extra.setText(ask.optString("text"));
            extra.setMaxLines(3);
            extra.setBackground(null);
            extra.setPadding(Ui.dp((Context)this, 4), Ui.dp((Context)this, 6), Ui.dp((Context)this, 4), Ui.dp((Context)this, 6));
            card.addView((View)extra, Ui.fill());
        }
        LinearLayout actions = Ui.row((Context)this);
        actions.setGravity(16);
        LinearLayout.LayoutParams actionSpace = new LinearLayout.LayoutParams(-1, -2);
        actionSpace.topMargin = Ui.dp((Context)this, 4);
        card.addView((View)actions, actionSpace);
        EditText freeText = extra;
        if (ask.optBoolean("canPrevious") && done != null) {
            MaterialButton previous = NativeUi.textButton(this, "上一步", () -> {
                JSONObject choice = this.questionChoice(selected, freeText, true);
                try { choice.put("previous", true); } catch (JSONException ignored) {}
                done.accept(choice);
            });
            previous.setTextSize(12f);
            actions.addView(previous, new LinearLayout.LayoutParams(-2, Ui.dp(this, 36)));
        }
        MaterialButton submit = NativeUi.primaryButton(this, ask.optString("submitLabel", "提交选择"), () -> {});
        submit.setTextSize(12.0f);
        actions.addView((View)submit, new LinearLayout.LayoutParams(0, Ui.dp((Context)this, 36), 1.0f));
        MaterialButton skip = NativeUi.textButton((Activity)this, "\u8df3\u8fc7", () -> {});
        skip.setTextSize(12.0f);
        LinearLayout.LayoutParams skipSpace = new LinearLayout.LayoutParams(-2, Ui.dp((Context)this, 36));
        skipSpace.leftMargin = Ui.dp((Context)this, 6);
        actions.addView((View)skip, skipSpace);
        submit.setOnClickListener(v -> {
            if (selected.isEmpty() && (freeText == null || freeText.getText().toString().trim().isEmpty())) {
                if (freeText != null) freeText.setError("请选择或填写回答");
                else NativeUi.toast(this, "请至少选择一个选项");
                return;
            }
            JSONObject answer = this.questionChoice(selected, freeText, true);
            if (done == null) {
                if (!AskQueue.answer(id2, answer)) NativeUi.toast(this, "这个问题已结束，请让 Agent 重新提问");
                this.askHost.removeView(panel);
            } else {
                done.accept(answer);
            }
        });
        skip.setOnClickListener(v -> {
            JSONObject answer = new JSONObject();
            try {
                answer.put("answered", false).put("reason", "\u7528\u6237\u8df3\u8fc7\u4e86\u9009\u62e9");
            }
            catch (JSONException jSONException) {
                // empty catch block
            }
            if (done == null) {
                if (!AskQueue.answer(id2, answer)) NativeUi.toast(this, "这个问题已结束，请让 Agent 重新提问");
                this.askHost.removeView(panel);
            } else {
                done.accept(answer);
            }
        });
        /* 只给选项区设上限：问题文字与底部按钮始终留在浮层里可见。 */
        optionScroll.post(() -> {
            if (card.getHeight() <= 0 || optionList.getChildCount() == 0) return;
            int chrome = Math.max(0, card.getHeight() - optionScroll.getHeight());
            optionScroll.setMaxHeight(Math.max(Ui.dp(this, 96), askOverlayMaxHeight() - chrome));
        });
        LinearLayout.LayoutParams panelSpace = new LinearLayout.LayoutParams(-1, -2);
        if (this.askHost.getChildCount() > 0) panelSpace.topMargin = Ui.dp(this, 10);
        this.askHost.addView(panel, panelSpace);
        this.followLatest = true;
    }

    private JSONObject questionChoice(ArrayList<String> selected, EditText text, boolean answered) {
        JSONArray values = new JSONArray();
        for (String value : selected) values.put(value);
        return NativeUi.json("answered", answered, "selected", values, "text", text == null ? "" : text.getText().toString());
    }

    private void showApproval(JSONObject request) {
        if (request == null) {
            return;
        }
        String id2 = request.optString("id");
        if (id2.isEmpty() || !this.approvals.add(id2)) {
            return;
        }
        if(request.has("workflowId"))this.collaborationApprovals.put(id2,request);
        if ("item/tool/requestUserInput".equals(request.optString("method"))) {
            this.renderQuestionCard(request, id2);
            return;
        }
        NativeApprovals.show((Activity)this, request, result -> this.answerApproval(id2,result,()->{}));
    }

    private void pickImage() {
        if (!(this.collaboration!=null&&this.collaboration.enabled())&&!this.modelSupportsImages(this.model)) {
            NativeUi.toast((Activity)this, "\u5f53\u524d\u6a21\u578b\u4e0d\u652f\u6301\u56fe\u7247\u8f93\u5165");
            return;
        }
        Intent pick = new Intent("android.intent.action.OPEN_DOCUMENT").setType("image/*").addCategory("android.intent.category.OPENABLE");
        try {
            this.startActivityForResult(pick, 72);
        }
        catch (Exception e) {
            NativeUi.error((Activity)this, "\u672a\u627e\u5230\u56fe\u7247\u9009\u62e9\u5668");
        }
    }

    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (request == 73) {
            if (result == -1) {
                this.addProjectFolder(uri);
            }
            return;
        }
        if (request != 72 || result != -1) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                RuntimeManager runtime = new RuntimeManager((Context)this, s -> {});
                File dir = new File(runtime.root, "opt/aixs/native/.uploads");
                dir.mkdirs();
                File target = new File(dir, UUID.randomUUID() + ".image");
                try (InputStream in = this.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(target);){
                    int n;
                    if (in == null) {
                        throw new IOException("\u65e0\u6cd5\u8bfb\u53d6\u56fe\u7247");
                    }
                    byte[] buffer = new byte[65536];
                    int total = 0;
                    while ((n = in.read(buffer)) != -1) {
                        if ((total += n) > 0xF00000) {
                            throw new IOException("\u5355\u5f20\u56fe\u7247\u4e0d\u80fd\u8d85\u8fc7 15 MB");
                        }
                        ((OutputStream)out).write(buffer, 0, n);
                    }
                }
                String display = NativeHomeActivity.displayName(this, uri);
                this.runOnUiThread(() -> {
                    JSONObject attachment = NativeUi.json("type", "localImage", "path", "/opt/aixs/native/.uploads/" + target.getName());
                    try {
                        if (display != null && !display.isEmpty()) attachment.put("name", display);
                    } catch (JSONException ignored) {
                    }
                    this.attachments.add(attachment);
                    this.updateAttach();
                    NativeUi.toast((Activity)this, "\u5df2\u6dfb\u52a0\u56fe\u7247");
                });
            }
            catch (Exception e) {
                this.runOnUiThread(() -> NativeUi.error((Activity)this, e.getMessage()));
            }
        });
    }

    private String defaultWorkspace() {
        File folder;
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) {
            home = this.getFilesDir().getParent();
        }
        if (!(folder = new File(home, "projects")).isDirectory()) {
            folder = new File(home);
        }
        return folder.getAbsolutePath();
    }

    private void persist() {
        this.viewCache.remember(this.thread, this.usage);
        this.getSharedPreferences("native-chat", 0).edit().putString("engine", this.engine).putString("cwd", this.cwd).putString("conversation-" + this.cwd.hashCode(), this.threadId).putString("model-" + this.engine, this.model).putString("thread-" + this.engine + "-" + this.cwd.hashCode(), this.threadId).putString("effort", this.effort).putString("draft-" + this.engine, this.composer == null ? "" : this.composer.getText().toString()).apply();
    }

    protected void onResume() {
        String next;
        super.onResume();
        NativeEngine.observers.addIfAbsent(this);
        if(this.collaboration!=null)this.collaboration.resume();
        SharedPreferences prefs = this.getSharedPreferences("native-chat", 0);
        String draft = prefs.getString("draft-" + this.engine, "");
        if (this.composer != null && !draft.equals(this.composer.getText().toString())) {
            this.composer.setText(draft);
        }
        if (!(next = prefs.getString("cwd", this.cwd)).equals(this.cwd)) {
            this.cwd = next;
            if (this.projectPill != null) {
                TextView label = (TextView)this.projectPill.getChildAt(this.projectPill.getChildCount() - 1);
                label.setText(NativeHomeActivity.folder(this.cwd));
            }
            this.updateSubtitle();
            if (this.turnId.isEmpty()) {
                this.thread = null;
                this.threadId = prefs.getString("conversation-" + this.cwd.hashCode(), prefs.getString("thread-" + this.engine + "-" + this.cwd.hashCode(), ""));
                this.resumed = false;
                this.renderMessages(false);
                if (this.threadId.isEmpty()) {
                    this.refreshUsage();
                    this.updateUsageRow();
                } else if (NativeEngine.ready()) {
                    this.loadThread(this.threadId);
                }
            }
        }
        if (NativeEngine.ready()) {
            this.refresh();
        }
    }

    protected void onDestroy() {
        AskQueue.unlisten(this.askListener);
        this.dismissStatsPopup();
        this.handler.removeCallbacks(this.ticker);
        this.collaborationRefresh.cancel();
        this.queueRefresh.cancel();
        this.repaint.cancel();
        if(this.collaboration!=null)this.collaboration.pause();
        super.onDestroy();
    }

    protected void onPause() {
        this.persist();
        if(this.collaboration!=null)this.collaboration.pause();
        this.collaborationRefresh.cancel();
        this.repaint.cancel();
        NativeEngine.observers.remove(this);
        super.onPause();
    }
}
