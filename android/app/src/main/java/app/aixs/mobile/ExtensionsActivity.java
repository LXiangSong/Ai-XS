package app.aixs.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;

/** One library for GitHub and imported extensions; assignment follows actual capabilities. */
public final class ExtensionsActivity extends AppCompatActivity {
  private static final String[] IDS = {"codex", "claude", "deepseek"};
  private LinearLayout body, results;
  private boolean preview;
  private int generation;
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() { back(); }
    });
    library();
  }
  private void back() { if (preview) library(); else finish(); }
  private int dp(int n) { return Ui.dp(this,n); }
  private void page(String subtitle) {
    generation++;
    LinearLayout root = NativeUi.page(this,"Skills 与插件",this::back);
    body = NativeUi.scroll(this,root);
    NativeUi.heading(this,body,preview ? "选择要安装的扩展" : "扩展库",subtitle);
  }
  private LinearLayout card(LinearLayout parent) {
    MaterialCardView shell = new MaterialCardView(this);
    shell.setRadius(dp(18)); shell.setCardBackgroundColor(Ui.card);
    shell.setStrokeWidth(dp(1)); shell.setStrokeColor(Ui.lineSoft);
    shell.setCardElevation(dp(2)); shell.setUseCompatPadding(true);
    LinearLayout content = Ui.col(this); Ui.pad(content,16,14,16,14);
    shell.addView(content);
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1,-2);
    space.bottomMargin=dp(10); parent.addView(shell,space);
    return content;
  }
  private void title(LinearLayout box,String name,String badge) {
    LinearLayout row=Ui.row(this);row.setGravity(Gravity.CENTER_VERTICAL);
    row.addView(Ui.text(this,name,17,Ui.text,Ui.medium(this)),new LinearLayout.LayoutParams(0,-2,1));
    TextView tag=Ui.text(this,badge,10.5f,Ui.onAccentSoft,Ui.medium(this));
    Ui.pad(tag,8,4,8,4);tag.setBackground(Ui.round(Ui.accentSoft,dp(20)));row.addView(tag);
    box.addView(row);
  }
  private void note(LinearLayout box,String text,int color) {
    if(text.isEmpty()) return;
    TextView v=Ui.text(this,text,12, color,null);v.setLineSpacing(dp(3),1);Ui.pad(v,0,7,0,3);box.addView(v);
  }
  private void library() {
    preview=false;page("从 GitHub 获取或导入本地 Skills，为一个、多个或全部 Agent 启用。插件会按组件检查兼容性。");
    LinearLayout actions=card(body);title(actions,"添加扩展","GitHub / 本地");
    actions.addView(NativeUi.primaryButton(this,"从 GitHub 获取",this::github));
    actions.addView(NativeUi.outlinedButton(this,"导入 Skills / 插件包",this::pickFile));
    actions.addView(NativeUi.textButton(this,"刷新扩展库",this::refresh));
    note(actions,"支持 ZIP 包和 SKILL.md。带脚本、模板等资源的 Skill 请整体打包为 ZIP。也可以直接让 Agent 使用扩展安装工具。",Ui.muted);
    results=Ui.col(this);body.addView(results);
    refresh();
  }
  private void refresh() {
    LinearLayout owner=results;int seq=generation;
    owner.removeAllViews();note(owner,"正在读取扩展…",Ui.muted);
    String cwd=getSharedPreferences("native-chat",0).getString("cwd","/root/projects");
    NativeEngine.call("native/extensions/list",NativeUi.json("cwd",cwd),r->{
      if(seq!=generation||isFinishing())return;
      owner.removeAllViews();JSONArray list=r.optJSONArray("data");
      NativeUi.section(this,owner,"已管理的扩展");
      if(list==null||list.length()==0)note(owner,"还没有扩展。添加后可以在卡片中调整 Agent 分配或停用。",Ui.muted);
      for(int i=0;list!=null&&i<list.length();i++) extensionCard(owner,list.optJSONObject(i),null);
      JSONArray external=r.optJSONArray("external");
      LinearLayout nativeBox=null;
      java.util.HashSet<String> seen=new java.util.HashSet<>();
      for(int i=0;external!=null&&i<external.length();i++) {
        JSONObject group=external.optJSONObject(i);String engine=group.optString("engine");
        JSONArray skills=group.optJSONArray("skills"),plugins=group.optJSONArray("plugins");
        if((skills==null||skills.length()==0)&&(plugins==null||plugins.length()==0))continue;
        if(nativeBox==null){NativeUi.section(this,owner,"Agent 自行安装的扩展");nativeBox=Ui.col(this);owner.addView(nativeBox);}
        for(int j=0;skills!=null&&j<skills.length();j++) {
          JSONObject item=skills.optJSONObject(j);
          if(!seen.add(item.optString("path")))continue;
          LinearLayout box=card(nativeBox);
          title(box,item.optString("name"),"原生 Skill");
          StringBuilder owners=new StringBuilder();
          for(int g=0;g<external.length();g++){
            JSONObject other=external.optJSONObject(g);JSONArray candidates=other.optJSONArray("skills");
            for(int s=0;candidates!=null&&s<candidates.length();s++)if(item.optString("path").equals(candidates.optJSONObject(s).optString("path"))){
              if(owners.length()>0)owners.append(" / ");owners.append(NativeUi.engineName(other.optString("engine")));break;
            }
          }
          note(box,owners+" · "+item.optString("description"),Ui.muted);
          note(box,item.optString("path"),Ui.faint);
          note(box,"原生目录中的版本仍由对应 Agent 管理；导入后得到可独立分配的副本。",Ui.faint);
          String path=item.optString("path");
          String source=path.endsWith("/SKILL.md")?path.substring(0,path.lastIndexOf('/')):path;
          box.addView(NativeUi.textButton(this,"导入扩展库并分配",()->importPath(source,item.optString("name"),false)));
        }
        for(int j=0;plugins!=null&&j<plugins.length();j++) {
          JSONObject item=plugins.optJSONObject(j);LinearLayout box=card(nativeBox);
          title(box,item.optString("name"),"原生插件");
          note(box,NativeUi.engineName(engine)+" · "+item.optString("version"),Ui.muted);
          note(box,"由 Agent 原生安装器管理；不能直接共享给其他 Agent。请从原始 GitHub 仓库导入后检查兼容性。",Ui.faint);
        }
        JSONArray errors=group.optJSONArray("errors");
        for(int j=0;errors!=null&&j<errors.length();j++)note(owner,errors.optString(j),Ui.warn);
      }
    },error->{if(seq==generation){owner.removeAllViews();note(owner,error,Ui.danger);owner.addView(NativeUi.textButton(this,"重试",this::refresh));}});
  }
  private void github() {
    LinearLayout box=Ui.col(this);Ui.pad(box,20,4,20,8);
    EditText url=NativeUi.field(this,"GitHub 仓库 / 目录链接","",false);
    EditText ref=NativeUi.field(this,"分支、Tag 或 Commit（可选）","",false);
    EditText path=NativeUi.field(this,"仓库子目录（可选）","",false);
    NativeUi.addField(this,box,url);NativeUi.addField(this,box,ref);NativeUi.addField(this,box,path);
    note(box,"例如 owner/repo，或 https://github.com/owner/repo/tree/main/skills。分支名包含 / 时请单独填写分支与子目录。",Ui.muted);
    AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle("从 GitHub 获取").setView(box)
      .setNegativeButton("取消",null).setPositiveButton("读取扩展",null).create();
    dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{
      if(url.getText().toString().trim().isEmpty()){url.setError("请填写仓库地址");return;}
      dialog.getButton(-1).setEnabled(false);dialog.getButton(-1).setText("读取中…");
      NativeEngine.call("native/extensions/preview",NativeUi.json("url",url.getText().toString().trim(),"ref",ref.getText().toString().trim(),"subdir",path.getText().toString().trim()),r->{
        if(isFinishing()||!dialog.isShowing())return;dialog.dismiss();showPreview(r);
      },e->{if(!isFinishing()&&dialog.isShowing()){dialog.getButton(-1).setEnabled(true);dialog.getButton(-1).setText("读取扩展");NativeUi.error(this,e);}});
    }));dialog.show();
  }
  private void showPreview(JSONObject value) {
    preview=true;page("先检查扩展内容与兼容状态，再选择需要使用它的 Agent。");
    JSONObject source=value.optJSONObject("source");
    note(body,source==null?"":source.optString("url")+" · "+source.optString("ref"),Ui.muted);
    JSONArray list=value.optJSONArray("data");
    for(int i=0;list!=null&&i<list.length();i++)extensionCard(body,list.optJSONObject(i),value.optString("previewId"));
  }
  private void extensionCard(LinearLayout parent,JSONObject item,String previewId) {
    boolean installed=previewId==null;
    LinearLayout box=card(parent);title(box,item.optString("name"),"skill".equals(item.optString("kind"))?"Skill":"插件");
    note(box,item.optString("description"),Ui.muted);
    JSONArray components=item.optJSONArray("components");
    if(components!=null)note(box,"组件："+join(components),Ui.faint);
    JSONObject source=item.optJSONObject("source");
    if(source!=null)note(box,source.optString("url"),Ui.faint);
    JSONObject compatibility=item.optJSONObject("compatibility");
    JSONArray agents=item.optJSONArray("agents");
    for(String engine:IDS) {
      JSONObject status=compatibility==null?null:compatibility.optJSONObject(engine);
      boolean supported=status!=null&&status.optBoolean("supported");
      boolean enabled=contains(agents,engine);
      note(box,NativeUi.engineName(engine)+" · "+(!supported?"不兼容":enabled?"已启用":"可使用")+"\n"+(status==null?"无法识别兼容性":status.optString("reason")),supported?Ui.muted:Ui.warn);
    }
    JSONArray warnings=item.optJSONArray("runtimeWarnings");
    for(int i=0;warnings!=null&&i<warnings.length();i++)note(box,warnings.optString(i),Ui.warn);
    box.addView(NativeUi.primaryButton(this,installed?"分配 Agent / 停用":"安装并分配",()->assign(item,previewId)));
    if(installed)box.addView(NativeUi.textButton(this,"移除扩展",()->new MaterialAlertDialogBuilder(this).setTitle("移除 "+item.optString("name")+"？")
      .setMessage("从扩展库移除，并停止在下一次任务中加载。Agent 自行安装的原始文件不受影响。")
      .setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->NativeEngine.call("native/extensions/remove",NativeUi.json("id",item.optString("id")),r->refresh(),e->NativeUi.error(this,e))).show()));
  }
  private static boolean contains(JSONArray values,String value) {for(int i=0;values!=null&&i<values.length();i++)if(value.equals(values.optString(i)))return true;return false;}
  private static String join(JSONArray values) {StringBuilder s=new StringBuilder();for(int i=0;i<values.length();i++){if(i>0)s.append(" · ");s.append(values.optString(i));}return s.toString();}
  private void assign(JSONObject item,String previewId) {
    LinearLayout box=Ui.col(this);Ui.pad(box,20,4,20,12);
    JSONObject compatibility=item.optJSONObject("compatibility");
    MaterialCheckBox[] choices=new MaterialCheckBox[IDS.length];
    box.addView(NativeUi.textButton(this,"选择全部可用 Agent",()->{for(MaterialCheckBox c:choices)if(c!=null&&c.isEnabled())c.setChecked(true);}));
    for(int i=0;i<IDS.length;i++) {
      String engine=IDS[i];JSONObject status=compatibility.optJSONObject(engine);
      MaterialCheckBox check=new MaterialCheckBox(this);choices[i]=check;
      check.setText(NativeUi.engineName(engine));check.setTextColor(Ui.text);
      check.setEnabled(status!=null&&status.optBoolean("supported"));check.setChecked(contains(item.optJSONArray("agents"),engine));
      box.addView(check);note(box,status==null?"无法识别兼容性":status.optString("reason"),check.isEnabled()?Ui.faint:Ui.warn);
    }
    note(box,"下一次任务生效。已运行的操作与会话记录不会因停用扩展而撤销。",Ui.muted);
    AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(previewId==null?"分配 Agent":"安装扩展")
      .setView(box).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
    dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{
      JSONArray agents=new JSONArray();for(int i=0;i<IDS.length;i++)if(choices[i].isEnabled()&&choices[i].isChecked())agents.put(IDS[i]);
      if(previewId!=null&&agents.length()==0){NativeUi.toast(this,"至少选择一个可用 Agent");return;}
      JSONObject params=NativeUi.json("agents",agents,"id",item.optString("id"),"previewId",previewId,"candidateId",item.optString("id"));
      dialog.getButton(-1).setEnabled(false);
      NativeEngine.call(previewId==null?"native/extensions/configure":"native/extensions/install",params,r->{
        if(isFinishing())return;dialog.dismiss();NativeUi.toast(this,r.optString("detail"));library();
      },e->{if(!isFinishing()){dialog.getButton(-1).setEnabled(true);NativeUi.error(this,e);}});
    }));dialog.show();
  }
  private void pickFile() {
    Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(intent,84);
  }
  @Override protected void onActivityResult(int request,int result,Intent data) {
    super.onActivityResult(request,result,data);
    if(request!=84||result!=RESULT_OK||data==null||data.getData()==null)return;
    android.net.Uri uri=data.getData();
    note(results,"正在导入本地文件…",Ui.muted);
    files.execute(()->{
      File temp=null;
      try {
        String label="Skills.zip";
        try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
          if(cursor!=null&&cursor.moveToFirst())label=cursor.getString(0);
        }
        RuntimeManager manager=new RuntimeManager(this,s->{});
        if(!manager.complete())throw new IOException("请先在设置中初始化并启动运行环境");
        temp=new File(manager.root,"tmp/extension-"+java.util.UUID.randomUUID()+(label.toLowerCase().endsWith(".md")?".md":".zip"));
        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)) {
          if(in==null)throw new IOException("无法读取文件");byte[] buffer=new byte[32768];int count,total=0;
          while((count=in.read(buffer))!=-1){total+=count;if(total>32*1024*1024)throw new IOException("本地包不能超过 32 MiB");out.write(buffer,0,count);}
        }
        File pending=temp;String display=label;
        runOnUiThread(()->{if(isFinishing()){pending.delete();return;}importPath("/tmp/"+pending.getName(),display,true);});
      }catch(Exception e){if(temp!=null)temp.delete();String error=e.getMessage();runOnUiThread(()->{if(!isFinishing())NativeUi.error(this,error);});}
    });
  }
  private void importPath(String path,String name,boolean temporary) {
    NativeEngine.call("native/extensions/import",NativeUi.json("path",path,"name",name),r->{cleanup(path,temporary);if(!isFinishing())showPreview(r);},e->{cleanup(path,temporary);if(!isFinishing())NativeUi.error(this,e);});
  }
  private void cleanup(String path,boolean temporary) {
    if(temporary)new File(new RuntimeManager(this,s->{}).root,path.substring(1)).delete();
  }
  @Override protected void onDestroy(){files.shutdown();super.onDestroy();}
}
