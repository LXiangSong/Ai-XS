package app.aixs.mobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** A process timeout must bound pipe draining too, even when a descendant holds stdout. */
final class RuntimeCommand {
  static final class Failure extends IOException {
    final String stage, output; final boolean timeout; final int exit;
    Failure(String stage,boolean timeout,int exit,String output) {
      super(stage + (timeout ? "：执行超时" : "：执行失败（退出码 " + exit + "）")
          + (output.isEmpty()?"；子进程没有输出":"\n最后输出："+output));
      this.stage=stage;this.timeout=timeout;this.exit=exit;this.output=output;
    }
  }
  static final class Tail {
    final StringBuilder text=new StringBuilder();
    synchronized void add(String line) {text.append(line).append('\n');if(text.length()>4000)text.delete(0,text.length()-4000);}
    synchronized String get() {return text.toString().trim();}
  }
  static long pid(Process process) {
    try {return ((Number)Process.class.getMethod("pid").invoke(process)).longValue();}
    catch(Exception ignored) {return -1;}
  }
  static String snapshot(Process process) {
    long pid=pid(process);if(pid<0)return "进程 PID 不可读取";
    StringBuilder out=new StringBuilder("PID="+pid);
    File[] all=new File("/proc").listFiles();if(all==null)return out.toString();
    Set<Long> owned=new HashSet<>();owned.add(pid);
    for(int pass=0;pass<4;pass++)for(File folder:all)try {
      if(!folder.getName().matches("[0-9]+"))continue;
      String stat=RuntimeFiles.read(new File(folder,"stat"));int end=stat.lastIndexOf(')');
      if(end<0)continue;String[] parts=stat.substring(end+2).split(" ");
      if(owned.contains(Long.parseLong(parts[1])))owned.add(Long.parseLong(folder.getName()));
    }catch(Exception ignored){}
    for(Long id:owned)try {
      String status=RuntimeFiles.read(new File("/proc/"+id+"/status"));
      out.append("\nPID ").append(id);
      for(String line:status.split("\n"))if(line.startsWith("Name:")||line.startsWith("State:")||line.startsWith("PPid:")||line.startsWith("VmRSS:"))out.append(' ').append(line);
      String wait=RuntimeFiles.read(new File("/proc/"+id+"/wchan")).trim();if(!wait.isEmpty())out.append(" wchan=").append(wait);
    }catch(Exception e){out.append(" [状态读取受限]");}
    return out.toString();
  }
  static String run(ProcessBuilder builder,String stage,int seconds,Consumer<String> output,Consumer<String> diagnostic) throws Exception {
    builder.redirectErrorStream(true);long start=System.nanoTime();
    Process child;
    try{child=builder.start();}catch(IOException e){throw new IOException(stage+"：无法启动子进程 · "+e.getMessage(),e);}
    Tail tail=new Tail();
    ExecutorService reader=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"runtime-output");t.setDaemon(true);return t;});
    Future<?> draining=reader.submit(()->{
      try(BufferedReader in=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))){
        String line;while((line=in.readLine())!=null){if(line.length()>2000)line=line.substring(0,2000);tail.add(line);output.accept(line);}
      }catch(IOException e){tail.add("[输出管道关闭："+e.getMessage()+"]");}
    });
    try {
      boolean done=child.waitFor(seconds,TimeUnit.SECONDS);
      if(!done){diagnostic.accept(stage+" · 超时前进程状态\n"+snapshot(child));child.destroyForcibly();child.waitFor(2,TimeUnit.SECONDS);}
      try{draining.get(done?2:1,TimeUnit.SECONDS);}catch(TimeoutException e){diagnostic.accept(stage+" · 输出管道仍被子进程持有");draining.cancel(true);}catch(ExecutionException e){diagnostic.accept(stage+" · 输出读取失败："+e.getCause());}
      int exit=child.isAlive()?-1:child.exitValue();
      diagnostic.accept(stage+" · exit="+exit+" · 耗时 "+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)+"ms");
      if(!done||exit!=0)throw new Failure(stage,!done,exit,tail.get());
      return tail.get();
    }finally{
      if(child.isAlive())child.destroyForcibly();
      reader.shutdownNow();
      // Closing a blocking Android pipe may itself block; keep it out of the caller's timeout path.
      Thread closer=new Thread(()->{try{child.getInputStream().close();child.getOutputStream().close();child.getErrorStream().close();}catch(IOException ignored){}},"runtime-pipe-close");closer.setDaemon(true);closer.start();
    }
  }
}
