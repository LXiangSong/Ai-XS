package app.aixs.mobile;

import android.content.Intent;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.*;
import java.util.*;

/** Native collaboration plans, participants, task progress and isolated results. */
public final class CollaborationActivity extends AppCompatActivity {
  private String page="list", workflowId="", taskId="", conversationId="", filter="all";
  private LinearLayout body;
  private JSONObject workflow, draft;
  private TextView summary, errorView;
  private boolean reading, saving, resumed, settingsFromList;
  private String listSignature="";
  private int generation;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final Map<String,TextView> statuses=new HashMap<>();
  private final Set<String> pendingRequests=new HashSet<>();
  private final Runnable refresh=new Runnable(){public void run(){poll();handler.postDelayed(this,1500);}};

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    page=getIntent().getStringExtra("page")==null?"list":getIntent().getStringExtra("page");
    conversationId=getIntent().getStringExtra("conversationId")==null?"":getIntent().getStringExtra("conversationId");
    if(state!=null){page=state.getString("page",page);workflowId=state.getString("workflow","");taskId=state.getString("task","");conversationId=state.getString("conversationId",conversationId);filter=state.getString("filter","all");settingsFromList=state.getBoolean("settingsFromList");}
    try{draft=new JSONObject(getPreferences(0).getString("draft-"+conversationId,"{}"));}catch(Exception e){draft=new JSONObject();}
    getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){public void handleOnBackPressed(){back();}});
    render();
  }
  private void request(String action,JSONObject params,java.util.function.Consumer<JSONObject> done){
    int owner=generation;
    NativeEngine.call("native/collaboration/"+action,params,r->{if(!isDestroyed()&&owner==generation)done.accept(r);},e->{
      if(isDestroyed()||owner!=generation)return;
      saving=false;errorView.setText(e+"\n需要诊断时可查看运行日志。");errorView.setVisibility(View.VISIBLE);
    });
  }
  private JSONObject params(){return NativeUi.json("workflowId",workflowId,"taskId",taskId);}
  private void error(String value){NativeUi.error(this,value);}
  private void persistDraft(){getPreferences(0).edit().putString("draft-"+conversationId,draft.toString()).apply();}
  private void render(){
    generation++;reading=false;statuses.clear();
    LinearLayout root=NativeUi.page(this,page.equals("editor")?"新建协作":page.equals("settings")?"协作与资源管理":page.equals("task")?"子任务":page.equals("detail")?"协作详情":"协作",this::back);
    body=NativeUi.scroll(this,root);
    errorView=NativeUi.body(this,"");errorView.setTextColor(Ui.warn);errorView.setVisibility(View.GONE);body.addView(errorView);
    if(!NativeEngine.ready()){
      body.addView(NativeUi.body(this,"共享服务尚未启动。协作复用已安装环境，请到 Agent 管理启动。"));
      body.addView(NativeUi.primaryButton(this,"打开 Agent 管理",()->startActivity(new Intent(this,RuntimeActivity.class).putExtra("page","agents").putExtra("returnToCaller",true))));return;
    }
    if(page.equals("editor")){editor();return;}
    if(page.equals("settings")){settings();return;}
    if(page.equals("list")){listing();return;}
    request("read",params(),r->{workflow=r.optJSONObject("workflow");if(workflow==null)return;getPreferences(0).edit().putInt("revision-"+workflowId,workflow.optInt("revision")).apply();
      if(page.equals("task"))taskPage();else detail();
    });
  }
  private TextView note(LinearLayout parent,String text){TextView v=NativeUi.body(this,text);v.setTextSize(12.5f);parent.addView(v);return v;}
  private LinearLayout card(){LinearLayout c=Ui.group(body);Ui.pad(c,14,12,14,12);return c;}
  private void heading(LinearLayout c,String title,String detail){c.addView(Ui.text(this,title,16,Ui.text,Ui.medium(this)));if(!detail.isEmpty())note(c,detail);}
  private JSONArray array(JSONObject o,String key){JSONArray a=o.optJSONArray(key);if(a==null){a=new JSONArray();try{o.put(key,a);}catch(Exception ignored){}}return a;}
  private JSONObject selectedTask(){for(int i=0;i<array(workflow,"tasks").length();i++){JSONObject t=array(workflow,"tasks").optJSONObject(i);if(t.optString("id").equals(taskId))return t;}return new JSONObject();}
  private JSONObject participant(String id){for(int i=0;i<array(workflow,"participants").length();i++){JSONObject p=array(workflow,"participants").optJSONObject(i);if(p.optString("id").equals(id))return p;}return new JSONObject();}
  private String who(JSONObject task){JSONObject p=participant(task.optString("participantId"));return p.optString("name")+" · "+NativeUi.engineName(p.optString("engine"))+" · "+p.optString("model");}
  static String state(String s){switch(s){case"draft":return"草稿";case"blocked":return"等待依赖";case"queued":return"排队";case"starting":return"准备工作区";case"running":return"运行中";case"waiting_input":return"等待回答";case"stopping":return"正在停止";case"succeeded":return"执行完成 · 待验收";case"failed":return"失败";case"interrupted":return"已中断";case"needs_reconciliation":return"待核对状态";case"paused":return"暂停派发";case"cancelled":return"已取消";case"awaiting_acceptance":return"待验收";case"completed":return"已验收";case"partial_failure":return"部分失败";default:return s;}}
  private boolean active(JSONObject t){return Arrays.asList("starting","running","waiting_input","stopping").contains(t.optString("status"));}
  private String statusText(JSONObject t){return state(t.optString("status"))+(t.optString("waitReason").isEmpty()?"":" · "+t.optString("waitReason"))+(t.optString("error").isEmpty()?"":"\n"+t.optString("error"));}
  private void listing(){
    LinearLayout actions=Ui.row(this);
    actions.addView(NativeUi.primaryButton(this,"新建协作",()->{workflowId="";workflow=null;try{draft=new JSONObject(getPreferences(0).getString("draft-"+conversationId,"{}"));}catch(Exception e){draft=new JSONObject();}page="editor";render();}),new LinearLayout.LayoutParams(0,-2,1));
    actions.addView(NativeUi.textButton(this,"资源设置",()->{settingsFromList=true;page="settings";render();}),new LinearLayout.LayoutParams(0,-2,1));body.addView(actions);
    List<String> labels=Arrays.asList("全部","进行中","待处理","已结束"), values=Arrays.asList("all","active","attention","ended");
    com.google.android.material.textfield.MaterialAutoCompleteTextView selector=NativeUi.dropdown(this,body,"筛选",labels,labels.get(Math.max(0,values.indexOf(filter))));
    selector.setOnItemClickListener((parent,view,position,id)->{filter=values.get(position);render();});
    summary=note(body,"读取协作列表…");
    request("list",conversationId.isEmpty()?new JSONObject():NativeUi.json("conversationId",conversationId),r->{
      JSONArray docs=r.optJSONArray("data");listSignature=listSignature(docs);resourceText(r.optJSONObject("resources"));
      if(docs==null||docs.length()==0){note(body,"还没有协作。可以添加相同 Agent 的多个模型，也可以混用三个 Agent。");return;}
      for(int i=0;i<docs.length();i++){
        JSONObject d=docs.optJSONObject(i);if(!matches(d))continue;LinearLayout c=card();heading(c,d.optString("title"),d.optString("cwd"));
        TextView status=note(c,state(d.optString("status"))+" · "+d.optInt("completed")+" / "+array(d,"tasks").length());statuses.put(d.optString("id"),status);
        c.addView(NativeUi.textButton(this,"查看协作",()->{workflowId=d.optString("id");page="detail";render();}));
      }
      if(statuses.isEmpty())note(body,"这个分类暂时没有协作。");
    });
  }
  private boolean matches(JSONObject d){String s=d.optString("status");if(filter.equals("active"))return s.equals("running");if(filter.equals("attention"))return !Arrays.asList("running","completed","cancelled").contains(s);if(filter.equals("ended"))return s.equals("completed")||s.equals("cancelled");return true;}
  private String listSignature(JSONArray docs){StringBuilder value=new StringBuilder();for(int i=0;docs!=null&&i<docs.length();i++){JSONObject d=docs.optJSONObject(i);if(matches(d))value.append(d.optString("id")).append(':').append(d.optString("status")).append(';');}return value.toString();}
  private void resourceText(JSONObject r){if(summary!=null&&r!=null)summary.setText("全局运行 "+r.optInt("running")+" / "+r.optInt("parallel",2)+" · 重任务 "+r.optInt("heavyRunning")+" / 1");}
  private void detail(){
    LinearLayout c=card();heading(c,workflow.optString("title"),workflow.optString("cwd"));summary=note(c,"");
    TextView status=note(c,state(workflow.optString("status"))+" · "+workflow.optInt("completed")+" / "+array(workflow,"tasks").length());statuses.put("workflow",status);
    note(c,workflow.optString("goal"));
    String s=workflow.optString("status");
    if(s.equals("draft")){
      c.addView(NativeUi.primaryButton(this,"确认计划并开始",()->action("start")));
      c.addView(NativeUi.textButton(this,"编辑计划",()->{draft=NativeUi.json("goal",workflow.optString("goal"),"title",workflow.optString("title"),"cwd",workflow.optString("cwd"),"participants",array(workflow,"participants"),"tasks",array(workflow,"tasks"),"leadParticipantId",workflow.optString("leadParticipantId"));persistDraft();page="editor";render();}));
      c.addView(NativeUi.textButton(this,"让负责人生成计划",()->new MaterialAlertDialogBuilder(this).setTitle("生成计划")
          .setMessage("将调用负责人模型产生一次计入用量的计划提案。生成后仍需你检查并点击开始。")
          .setNegativeButton("取消",null).setPositiveButton("生成",(d,w)->action("plan")).show()));
    }else if(s.equals("running"))c.addView(NativeUi.textButton(this,"暂停派发",()->action("pause")));
    else if(s.equals("paused")||s.equals("partial_failure"))c.addView(NativeUi.primaryButton(this,"恢复派发",()->action("resume")));
    if(s.equals("awaiting_acceptance")){
      note(c,"执行完成不代表成果已验收。请查看任务结果、补丁与测试证据，再确认交付。");
      c.addView(NativeUi.primaryButton(this,"确认成果并验收",()->action("accept")));
    }
    c.addView(NativeUi.textButton(this,"回到独立模式",this::independent));
    if(workflow.optInt("active")==0)c.addView(NativeUi.textButton(this,"接收最新成果到原会话",()->request("handoff",params(),r->NativeUi.toast(this,"已接收到原会话，下次任务会携带成果"))));
    if(!s.equals("completed")&&!s.equals("cancelled"))c.addView(NativeUi.textButton(this,"取消协作",()->new MaterialAlertDialogBuilder(this).setTitle("取消协作？").setMessage("停止派发并中断本协作实例，保留已完成成果。")
        .setNegativeButton("返回",null).setPositiveButton("取消协作",(d,w)->action("cancel")).show()));
    if(!workflow.optString("planError").isEmpty())note(c,workflow.optString("planError"));
    NativeUi.section(this,body,"参与者");
    for(int i=0;i<array(workflow,"participants").length();i++){
      JSONObject p=array(workflow,"participants").optJSONObject(i);LinearLayout pc=card();heading(pc,p.optString("name"),NativeUi.engineName(p.optString("engine"))+" · "+p.optString("model")+" · "+(p.optString("permission").equals("auto")?"自动执行":"询问权限"));note(pc,p.optString("role"));
    }
    NativeUi.section(this,body,"子任务");
    for(int i=0;i<array(workflow,"tasks").length();i++){
      JSONObject t=array(workflow,"tasks").optJSONObject(i);LinearLayout tc=card();heading(tc,t.optString("title"),who(t));
      TextView v=note(tc,statusText(t));statuses.put(t.optString("id"),v);
      if(array(t,"dependencies").length()>0)note(tc,"依赖："+array(t,"dependencies").toString());
      tc.addView(NativeUi.textButton(this,"进度、结果与操作",()->{taskId=t.optString("id");page="task";render();}));
    }
    showWaiting();
  }
  private void action(String action){if(saving)return;saving=true;request(action,params(),r->{saving=false;render();});}
  private void independent(){
    if(workflow.optInt("active")>0){
      new MaterialAlertDialogBuilder(this).setTitle("回到独立模式").setItems(new String[]{"协作转入后台，独立任务按资源排队","中断本协作任务后返回"},(d,i)->returnIndependent(i==1)).setNegativeButton("取消",null).show();
    }else returnIndependent(false);
  }
  private void returnIndependent(boolean interrupt){
    JSONObject p=params();try{p.put("mode","independent").put("interrupt",interrupt);}catch(Exception ignored){}
    request("mode",p,r->{
      startActivity(new Intent(this,NativeHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
          .putExtra("conversationId",workflow.optString("conversationId")));finish();
    });
  }
  private void taskPage(){
    JSONObject t=selectedTask();LinearLayout c=card();heading(c,t.optString("title"),who(t));
    TextView status=note(c,statusText(t));statuses.put("task",status);
    note(c,t.optString("goal"));note(c,"验收："+t.optString("acceptance"));
    TextView progress=note(c,t.optJSONObject("progress")==null?"":t.optJSONObject("progress").optString("detail"));statuses.put("progress",progress);
    if(t.optString("status").equals("needs_reconciliation"))c.addView(NativeUi.outlinedButton(this,"核对后允许继续",()->new MaterialAlertDialogBuilder(this).setTitle("确认已核对执行状态？")
        .setMessage("请检查旧工作区、已运行的命令和未知操作。系统还会检查已记录的进程；存活时不释放资源。")
        .setNegativeButton("取消",null).setPositiveButton("已核对",(d,w)->request("reconcile",NativeUi.json("workflowId",workflowId,"taskId",taskId,"confirmed",true),r->render())).show()));
    if(active(t)){
      c.addView(NativeUi.outlinedButton(this,"停止此子任务",()->action("stop")));
      c.addView(NativeUi.textButton(this,"更换执行者 / 模型",()->participantDialog(null,p->new MaterialAlertDialogBuilder(this).setTitle("新配置何时生效？")
          .setItems(new String[]{"当前任务继续，仅保存下次执行配置","中断当前执行并交接给新配置"},(dialog,index)->{
            JSONObject args=params();try{args.put("participant",p).put("when",index==0?"after":"interrupt");}catch(Exception ignored){}
            request("reassign",args,r->render());
          }).setNegativeButton("取消",null).show())));
    }
    else{
      c.addView(NativeUi.textButton(this,"继续 / 重试",()->new MaterialAlertDialogBuilder(this).setTitle("新建一次执行尝试？").setMessage("保留旧尝试与用量。检查已有成果，避免重复有副作用的操作。已完成任务重做会使下游成果失效。")
        .setNegativeButton("取消",null).setPositiveButton("继续",(d,w)->action("retry")).show()));
      c.addView(NativeUi.textButton(this,"更换 Agent / 模型",()->participantDialog(null,p->{JSONObject args=params();try{args.put("participant",p);}catch(Exception ignored){}request("reassign",args,r->render());})));
    }
    LinearLayout result=card();heading(result,"结果与成果","");
    TextView output=note(result,active(t)&&!t.optString("liveText").isEmpty()?t.optString("liveText"):t.optString("result","尚无结果"));output.setTextIsSelectable(true);statuses.put("result",output);
    JSONObject artifact=t.optJSONObject("artifact");
    if(artifact!=null){
      note(result,artifact.optString("integration"));JSONObject w=artifact.optJSONObject("workspace");if(w!=null)note(result,"独立工作区："+w.optString("path"));
      if(!artifact.optString("patch").isEmpty()){
        note(result,"补丁："+artifact.optString("patch"));
        if(t.optString("status").equals("succeeded"))result.addView(NativeUi.outlinedButton(this,"检查并合入原项目",()->new MaterialAlertDialogBuilder(this).setTitle("应用此任务补丁？")
            .setMessage("先核对原项目无改动且基线相同；冲突会停止。应用后不自动提交，请继续测试。")
            .setNegativeButton("取消",null).setPositiveButton("合入",(d,b)->request("integrate",params(),r->{NativeUi.toast(this,r.optString("message"));render();})).show()));
      }
    }
    NativeUi.section(this,body,"尝试与用量");
    JSONArray attempts=array(t,"attempts");
    for(int i=0;i<attempts.length();i++){
      JSONObject a=attempts.optJSONObject(i);JSONObject config=a.optJSONObject("config");LinearLayout ac=card();
      heading(ac,"尝试 "+(i+1)+" · "+state(a.optString("status")),config==null?"":NativeUi.engineName(config.optString("engine"))+" · "+config.optString("model"));
      JSONObject usage=a.optJSONObject("usage");note(ac,"DeepSeek".equals(config==null?"":NativeUi.engineName(config.optString("engine")))?"上下文统计不作为计费 Token 合计":usage==null?"用量未报告":usage.toString());
      note(ac,"执行编号："+a.optString("id"));
      ac.addView(NativeUi.textButton(this,"查看此次执行日志",()->request("logs",NativeUi.json("workflowId",workflowId,"taskId",taskId,"attemptId",a.optString("id")),r->{
        TextView log=NativeUi.body(this,r.optString("text","暂无日志"));log.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(log);
        new MaterialAlertDialogBuilder(this).setTitle("执行诊断（已脱敏）").setView(scroll).setPositiveButton("关闭",null).show();
      })));
    }
    showWaiting();
  }
  private void showWaiting(){
    for(int i=0;i<array(workflow,"tasks").length();i++){
      JSONObject t=array(workflow,"tasks").optJSONObject(i);JSONArray approvals=t.optJSONArray("approvals");
      for(int j=0;approvals!=null&&j<approvals.length();j++){
        JSONObject a=approvals.optJSONObject(j);LinearLayout c=card();heading(c,"待回答 · "+t.optString("title"),who(t));
        c.addView(NativeUi.primaryButton(this,"查看请求并回答",()->NativeApprovals.show(this,a,answer->{
          JSONObject p=NativeUi.json("workflowId",workflowId,"taskId",t.optString("id"),"attemptId",a.optString("attemptId"),"id",a.optString("id"),"result",answer);
          request("respond",p,r->render());
        })));
      }
    }
    for(AskQueue.Pending pending:AskQueue.snapshot()){
      JSONObject ask=pending.ask,execution=ask.optJSONObject("execution");if(execution==null||!workflowId.equals(execution.optString("workflowId")))continue;
      LinearLayout c=card();heading(c,"待回答 · "+execution.optString("participant"),ask.optString("question"));
      c.addView(NativeUi.primaryButton(this,"回答选择题",()->answerQuestion(ask)));
    }
  }
  private void answerQuestion(JSONObject ask){
    JSONArray questions=ask.optJSONArray("questions");if(questions==null)questions=new JSONArray().put(ask);
    JSONObject request=NativeUi.json("method","item/tool/requestUserInput","params",NativeUi.json("questions",questions));
    NativeApprovals.show(this,request,value->{try{value.put("answered",true);}catch(Exception ignored){}if(!AskQueue.answer(ask.optString("id"),value))error("问题已结束");else render();});
  }
  private void settings(){
    LinearLayout c=card();heading(c,"共享运行环境","所有普通会话和协作任务共同使用执行名额。默认最多 2 个执行，重任务最多 1 个；3 并行为实验选项。");
    request("settings",new JSONObject(),r->{
      com.google.android.material.textfield.MaterialAutoCompleteTextView limit=NativeUi.dropdown(this,c,"全局并行上限",Arrays.asList("1","2","3"),String.valueOf(r.optInt("parallel",2)));
      com.google.android.material.textfield.MaterialAutoCompleteTextView source=NativeUi.dropdown(this,c,"同 API 网关并发上限",Arrays.asList("1","2","3"),String.valueOf(r.optInt("sourceParallel",2)));
      c.addView(NativeUi.primaryButton(this,"保存资源设置",()->request("settings",NativeUi.json("parallel",Integer.parseInt(limit.getText().toString()),"sourceParallel",Integer.parseInt(source.getText().toString())),v->NativeUi.toast(this,"已保存，新任务按新上限排队"))));
    });
    note(c,"可用内存低于 1 GiB、系统报告低内存或严重发热时暂停新派发。已经运行的任务不会被假装冻结。手机与浏览器资源按任务独占。");
    note(c,"每次执行独立工作区。Git 项目须先提交现有改动；非 Git 使用上限 200 MiB 的独立快照。成果不会自动覆盖原项目。");
  }
  private EditText field(LinearLayout c,String label,String value,boolean multi,java.util.function.Consumer<String> save){
    EditText f=NativeUi.field(this,label,value,multi);NativeUi.addField(this,c,f);f.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int n,int x){}public void onTextChanged(CharSequence s,int a,int b,int n){save.accept(s.toString());}public void afterTextChanged(Editable e){}});return f;
  }
  private void putDraft(String key,String value){try{draft.put(key,value);}catch(Exception ignored){}persistDraft();}
  private void editor(){
    LinearLayout c=card();heading(c,"协作目标","保存草稿不调用模型。确认计划并开始后，才派发任务。");
    field(c,"目标",draft.optString("goal"),true,v->putDraft("goal",v));
    field(c,"名称（可选）",draft.optString("title"),false,v->putDraft("title",v));
    String cwd=draft.optString("cwd",getIntent().getStringExtra("cwd")==null?getSharedPreferences("native-chat",0).getString("cwd","/root/projects"):getIntent().getStringExtra("cwd"));
    putDraft("cwd",cwd);field(c,"项目目录",cwd,false,v->putDraft("cwd",v)).setEnabled(conversationId.isEmpty()&&workflowId.isEmpty());
    NativeUi.section(this,body,"参与者（允许同 Agent 多模型）");
    JSONArray people=array(draft,"participants");
    for(int i=0;i<people.length();i++){
      final int index=i;JSONObject p=people.optJSONObject(i);LinearLayout pc=card();heading(pc,p.optString("name"),NativeUi.engineName(p.optString("engine"))+" · "+p.optString("model"));
      note(pc,p.optString("role"));pc.addView(NativeUi.textButton(this,"编辑参与者",()->participantDialog(p,value->{try{people.put(index,value);}catch(Exception ignored){}persistDraft();render();})));
      pc.addView(NativeUi.textButton(this,"设为负责人"+(p.optString("id").equals(draft.optString("leadParticipantId"))?" · 当前":""),()->{putDraft("leadParticipantId",p.optString("id"));render();}));
      pc.addView(NativeUi.textButton(this,"移除参与者",()->{for(int j=0;j<array(draft,"tasks").length();j++)if(array(draft,"tasks").optJSONObject(j).optString("participantId").equals(p.optString("id"))){error("请先把此参与者的任务分配给其他人");return;}people.remove(index);if(p.optString("id").equals(draft.optString("leadParticipantId")))putDraft("leadParticipantId",people.length()==0?"":people.optJSONObject(0).optString("id"));persistDraft();render();}));
    }
    body.addView(NativeUi.outlinedButton(this,"添加参与者",()->participantDialog(null,value->{people.put(value);if(draft.optString("leadParticipantId").isEmpty())putDraft("leadParticipantId",value.optString("id"));persistDraft();render();})));
    NativeUi.section(this,body,"计划");
    JSONArray tasks=array(draft,"tasks");
    for(int i=0;i<tasks.length();i++){
      final int index=i;JSONObject t=tasks.optJSONObject(i);LinearLayout tc=card();heading(tc,t.optString("id")+" · "+t.optString("title"),t.optString("goal"));
      tc.addView(NativeUi.textButton(this,"编辑任务与依赖",()->taskDialog(t,value->{try{tasks.put(index,value);}catch(Exception ignored){}persistDraft();render();})));
      tc.addView(NativeUi.textButton(this,"移除此任务",()->{tasks.remove(index);persistDraft();render();}));
    }
    body.addView(NativeUi.outlinedButton(this,"添加子任务",()->taskDialog(null,value->{tasks.put(value);persistDraft();render();})));
    if(tasks.length()==0)body.addView(NativeUi.textButton(this,"添加默认工作流程",()->{template();persistDraft();render();}));
    body.addView(NativeUi.primaryButton(this,"保存可编辑计划",this::saveDraft));
  }
  private void template(){
    JSONArray p=array(draft,"participants");if(p.length()==0){error("请先添加参与者");return;}
    JSONArray tasks=array(draft,"tasks");String[] labels={"分析与方案","实现 / 整理成果","审核与验证","汇总交付"};
    for(int i=0;i<labels.length;i++)tasks.put(NativeUi.json("id","t"+(i+1),"title",labels[i],"goal",labels[i]+"："+draft.optString("goal"),
        "participantId",p.optJSONObject(i%p.length()).optString("id"),"dependencies",i==0?new JSONArray():new JSONArray().put("t"+i),"acceptance","给出可核对的成果与验证结论"));
  }
  private void saveDraft(){
    if(saving)return;saving=true;JSONObject p;
    try{p=new JSONObject(draft.toString());p.put("conversationId",conversationId);p.put("clientRequestId",draft.optString("clientRequestId",UUID.randomUUID().toString()));draft.put("clientRequestId",p.optString("clientRequestId"));persistDraft();
      if(!workflowId.isEmpty()){p.put("workflowId",workflowId);p.put("revision",workflow==null?getPreferences(0).getInt("revision-"+workflowId,-1):workflow.optInt("revision"));}
    }catch(Exception e){saving=false;error(e.getMessage());return;}
    request(workflowId.isEmpty()?"create":"save",p,r->{saving=false;getPreferences(0).edit().remove("draft-"+conversationId).apply();workflow=r.optJSONObject("workflow");workflowId=workflow.optString("id");conversationId=workflow.optString("conversationId");draft=new JSONObject();page="detail";render();});
  }
  private void participantDialog(JSONObject original,java.util.function.Consumer<JSONObject> done){
    JSONObject value=original==null?new JSONObject():original;
    LinearLayout c=NativeUi.column(this);Ui.pad(c,16,4,16,10);ScrollView scroll=new ScrollView(this);scroll.addView(c);
    com.google.android.material.textfield.MaterialAutoCompleteTextView agent=NativeUi.dropdown(this,c,"Agent",Arrays.asList("codex","claude","deepseek"),value.optString("engine","codex"));
    EditText name=NativeUi.field(this,"参与者名称",value.optString("name"),false);NativeUi.addField(this,c,name);
    EditText role=NativeUi.field(this,"职责",value.optString("role"),true);NativeUi.addField(this,c,role);
    EditText model=NativeUi.field(this,"模型 ID",value.optString("model"),false);NativeUi.addField(this,c,model);
    c.addView(NativeUi.textButton(this,"选择已配置模型",()->NativeEngine.call("model/list",NativeUi.json("engine",agent.getText().toString()),r->{
      JSONArray data=r.optJSONArray("data");ArrayList<String> ids=new ArrayList<>();for(int i=0;data!=null&&i<data.length();i++)ids.add(data.optJSONObject(i).optString("model"));
      new MaterialAlertDialogBuilder(this).setTitle("选择模型").setItems(ids.toArray(new String[0]),(d,i)->model.setText(ids.get(i))).show();
    },this::error)));
    com.google.android.material.textfield.MaterialAutoCompleteTextView effort=NativeUi.dropdown(this,c,"思考强度（留空沿用模型默认）",Arrays.asList("","off","low","medium","high","xhigh","max"),value.optString("effort"));
    com.google.android.material.textfield.MaterialAutoCompleteTextView permission=NativeUi.dropdown(this,c,"权限",Arrays.asList("ask","auto"),value.optString("permission","ask"));
    com.google.android.material.textfield.MaterialAutoCompleteTextView mode=NativeUi.dropdown(this,c,"API 来源",Arrays.asList("current","official","thirdparty"),value.optString("apiMode","current"));
    note(c,"current 使用启动时当前 API。每次执行固定配置快照；不会改写全局设置。ask 为询问权限，auto 为自动执行。");
    androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle("参与者执行配置").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
    dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{if(model.getText().toString().trim().isEmpty()){model.setError("请选择模型");return;}
      done.accept(NativeUi.json("id",value.optString("id",UUID.randomUUID().toString()),"name",name.getText().toString().isEmpty()?agent.getText().toString():name.getText().toString(),"role",role.getText().toString(),
          "engine",agent.getText().toString(),"model",model.getText().toString().trim(),"effort",effort.getText().toString(),"permission",permission.getText().toString(),"apiMode",mode.getText().toString()));dialog.dismiss();}));dialog.show();
  }
  private void taskDialog(JSONObject original,java.util.function.Consumer<JSONObject> done){
    JSONArray people=array(draft,"participants");if(people.length()==0){error("请先添加参与者");return;}
    JSONObject value=original==null?new JSONObject():original;LinearLayout c=NativeUi.column(this);Ui.pad(c,16,4,16,10);ScrollView scroll=new ScrollView(this);scroll.addView(c);
    EditText title=NativeUi.field(this,"任务名称",value.optString("title"),false);NativeUi.addField(this,c,title);
    EditText goal=NativeUi.field(this,"具体目标",value.optString("goal"),true);NativeUi.addField(this,c,goal);
    EditText acceptance=NativeUi.field(this,"交付 / 验收条件",value.optString("acceptance"),true);NativeUi.addField(this,c,acceptance);
    ArrayList<String> labels=new ArrayList<>();int selected=0;for(int i=0;i<people.length();i++){JSONObject p=people.optJSONObject(i);labels.add((i+1)+" · "+p.optString("name")+" · "+p.optString("model"));if(p.optString("id").equals(value.optString("participantId")))selected=i;}
    com.google.android.material.textfield.MaterialAutoCompleteTextView assigned=NativeUi.dropdown(this,c,"执行参与者",labels,labels.get(selected));
    JSONArray tasks=array(draft,"tasks");ArrayList<CheckBox> deps=new ArrayList<>();
    note(c,"依赖（不选可并行）：");for(int i=0;i<tasks.length();i++){JSONObject t=tasks.optJSONObject(i);if(t.optString("id").equals(value.optString("id")))continue;
      CheckBox check=new com.google.android.material.checkbox.MaterialCheckBox(this);check.setText(t.optString("title"));check.setTag(t.optString("id"));check.setChecked(array(value,"dependencies").toString().contains("\""+t.optString("id")+"\""));c.addView(check);deps.add(check);}
    CheckBox heavy=new com.google.android.material.checkbox.MaterialCheckBox(this);heavy.setText("重任务（构建 / 大型测试）");heavy.setChecked(value.optBoolean("heavy"));c.addView(heavy);
    CheckBox device=new com.google.android.material.checkbox.MaterialCheckBox(this);device.setText("需要手机控制（独占）");device.setChecked(value.optBoolean("device"));c.addView(device);
    CheckBox browser=new com.google.android.material.checkbox.MaterialCheckBox(this);browser.setText("需要开发浏览器（独占）");browser.setChecked(value.optBoolean("browser"));c.addView(browser);
    androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle("子任务与依赖").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
    dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{if(goal.getText().toString().trim().isEmpty()||acceptance.getText().toString().trim().isEmpty()){goal.setError("目标和验收条件均需填写");return;}
      JSONArray dependencies=new JSONArray();for(CheckBox check:deps)if(check.isChecked())dependencies.put(check.getTag());
      int index=Math.max(0,labels.indexOf(assigned.getText().toString()));done.accept(NativeUi.json("id",value.optString("id","t-"+UUID.randomUUID().toString().substring(0,8)),"title",title.getText().toString(),
          "goal",goal.getText().toString(),"acceptance",acceptance.getText().toString(),"participantId",people.optJSONObject(index).optString("id"),"dependencies",dependencies,"heavy",heavy.isChecked(),"device",device.isChecked(),"browser",browser.isChecked()));dialog.dismiss();}));dialog.show();
  }
  private void poll(){
    if(!resumed||reading||!NativeEngine.ready()||page.equals("editor")||page.equals("settings"))return;
    reading=true;int owner=generation;String action=page.equals("list")?"list":"read";
    JSONObject p=page.equals("list")?(conversationId.isEmpty()?new JSONObject():NativeUi.json("conversationId",conversationId)):params();
    NativeEngine.call("native/collaboration/"+action,p,r->{reading=false;if(owner!=generation||isDestroyed())return;resourceText(r.optJSONObject("resources"));
      if(page.equals("list")){JSONArray docs=r.optJSONArray("data");if(!listSignature.equals(listSignature(docs))){render();return;}for(int i=0;docs!=null&&i<docs.length();i++){JSONObject d=docs.optJSONObject(i);TextView v=statuses.get(d.optString("id"));if(v!=null)v.setText(state(d.optString("status"))+" · "+d.optInt("completed")+" / "+array(d,"tasks").length());}return;}
      JSONObject next=r.optJSONObject("workflow");if(next==null)return;
      String previous=workflow==null?"":workflow.optString("status");String previousTask=workflow==null?"":selectedTask().optString("status");workflow=next;
      if(!previous.equals(next.optString("status"))){render();return;}
      if(page.equals("task")){
        JSONObject t=selectedTask();if(!previousTask.equals(t.optString("status"))){render();return;}TextView v=statuses.get("task");if(v!=null)v.setText(statusText(t));v=statuses.get("result");String output=active(t)&&!t.optString("liveText").isEmpty()?t.optString("liveText"):t.optString("result");if(v!=null&&!output.contentEquals(v.getText()))v.setText(output);
        v=statuses.get("progress");if(v!=null&&t.optJSONObject("progress")!=null)v.setText(t.optJSONObject("progress").optString("detail"));
      }else{
        TextView v=statuses.get("workflow");if(v!=null)v.setText(state(next.optString("status"))+" · "+next.optInt("completed")+" / "+array(next,"tasks").length());
        for(int i=0;i<array(next,"tasks").length();i++){JSONObject t=array(next,"tasks").optJSONObject(i);v=statuses.get(t.optString("id"));if(v!=null)v.setText(statusText(t));}
      }
      Set<String> ids=new HashSet<>();for(int i=0;i<array(next,"tasks").length();i++){JSONArray a=array(array(next,"tasks").optJSONObject(i),"approvals");for(int j=0;j<a.length();j++)ids.add(a.optJSONObject(j).optString("id"));}
      for(AskQueue.Pending ask:AskQueue.snapshot()){JSONObject ex=ask.ask.optJSONObject("execution");if(ex!=null&&workflowId.equals(ex.optString("workflowId")))ids.add(ask.ask.optString("id"));}
      if(!ids.equals(pendingRequests)){pendingRequests.clear();pendingRequests.addAll(ids);render();}
    },e->{reading=false;if(owner==generation){errorView.setText(e);errorView.setVisibility(View.VISIBLE);}});
  }
  private void back(){if(page.equals("task")){page="detail";render();}else if(page.equals("detail")||(page.equals("settings")&&settingsFromList)){page="list";settingsFromList=false;render();}else if(page.equals("editor")){persistDraft();page=workflowId.isEmpty()?"list":"detail";render();}else finish();}
  @Override protected void onResume(){super.onResume();resumed=true;handler.post(refresh);if(NativeEngine.ready()&&body!=null&&body.getChildCount()<3)render();}
  @Override protected void onPause(){super.onPause();resumed=false;handler.removeCallbacks(refresh);}
  @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("page",page);out.putString("workflow",workflowId);out.putString("task",taskId);out.putString("conversationId",conversationId);out.putString("filter",filter);out.putBoolean("settingsFromList",settingsFromList);}
}
