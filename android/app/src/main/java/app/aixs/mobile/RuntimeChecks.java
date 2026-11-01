package app.aixs.mobile;

import java.util.*;

/** Independent executable probes: an executable existing on disk is not a health check. */
final class RuntimeChecks {
  static final class Probe {
    final String id,label;final int seconds;final String[] command;
    Probe(String id,String label,int seconds,String...command){this.id=id;this.label=label;this.seconds=seconds;this.command=command;}
  }
  static List<Probe> local(String nodeVersion) {
    return Arrays.asList(
      new Probe("launcher","PRoot、Shell 与 CPU",15,"/bin/sh","-ec","test \"$(uname -m)\" = aarch64; /usr/bin/env >/dev/null; echo RUNTIME_LAUNCH_OK"),
      new Probe("storage","临时目录与项目目录读写",10,"/bin/sh","-ec","mkdir -p /tmp /root/projects; p=$(mktemp /tmp/aixs-check.XXXXXX); printf OK > \"$p\"; test \"$(cat \"$p\")\" = OK; rm -f \"$p\"; test -w /root/projects; echo STORAGE_OK"),
      new Probe("python-start","Python 启动",15,"/usr/bin/python3","-I","-S","-c","import sys; assert sys.version_info >= (3,12); print(sys.version); print('PYTHON_START_OK', flush=True)"),
      new Probe("python-stdlib","Python 标准库、子进程与字节码编译",30,"/usr/bin/python3","-I","-c",
        "import tempfile, pathlib, subprocess, py_compile, sqlite3, ctypes, json; print('stdlib imported', flush=True); "
        +"d=tempfile.TemporaryDirectory(); p=pathlib.Path(d.name)/'probe.py'; p.write_text('value = 42\\n'); py_compile.compile(str(p),doraise=True); print('bytecode compiled', flush=True); "
        +"subprocess.run(['/bin/sh','-c','exit 0'],check=True,timeout=5); sqlite3.connect(':memory:').execute('select 1'); d.cleanup(); print('PYTHON_STDLIB_OK', flush=True)"),
      new Probe("tls","Python TLS 与 CA 证书加载",15,"/usr/bin/python3","-I","-c","import ssl; print(ssl.OPENSSL_VERSION, flush=True); c=ssl.create_default_context(); n=c.cert_store_stats()['x509_ca']; assert n>0, 'CA store empty'; print('TLS_CA_OK',n)"),
      new Probe("git","Git 启动与本地仓库创建",15,"/bin/sh","-ec","git --version; d=$(mktemp -d /tmp/aixs-git.XXXXXX); trap 'rm -rf \"$d\"' EXIT; git -c init.defaultBranch=main init -q \"$d\"; git -C \"$d\" rev-parse --git-dir; echo GIT_OK"),
      new Probe("curl","curl 与 TLS 后端",10,"/bin/sh","-ec","curl --version; curl --version | grep -q https; echo CURL_OK"),
      new Probe("xz","xz 压缩与解压",10,"/bin/sh","-ec","xz --version; test \"$(printf AIXS | xz -c | xz -dc)\" = AIXS; echo XZ_OK"),
      new Probe("node","Node.js 启动、文件读写与子进程",20,"/opt/node/bin/node","-e",
        "const fs=require('node:fs'),os=require('node:os'),p=require('node:path'),cp=require('node:child_process');"
        +"console.log(process.version);if(process.version!=='"+nodeVersion+"')throw Error('Node version does not match configured version');"
        +"const d=fs.mkdtempSync(p.join(os.tmpdir(),'aixs-node-'));try{fs.writeFileSync(p.join(d,'probe'),'OK');"
        +"if(fs.readFileSync(p.join(d,'probe'),'utf8')!=='OK')throw Error('file read mismatch');cp.execFileSync('/bin/sh',['-c','exit 0'],{timeout:5000});console.log('NODE_OK')}finally{fs.rmSync(d,{recursive:true,force:true})}"),
      new Probe("npm","npm 启动",20,"/opt/node/bin/npm","--version"),
      new Probe("dpkg","软件包配置完整性",15,"/bin/sh","-ec","out=$(dpkg --audit); if test -n \"$out\"; then printf '%s\\n' \"$out\"; exit 1; fi; echo DPKG_OK")
    );
  }
  private static Probe networkProbe(String id,String label,String url) {
    return new Probe(id,label,12,"/usr/bin/curl","--fail","--silent","--show-error","--location","--connect-timeout","4","--max-time","8","--output","/dev/null","--write-out","HTTP %{http_code}; DNS %{time_namelookup}s; connect %{time_connect}s; TLS %{time_appconnect}s\n",url);
  }
  static List<Probe> network(String aptMirror,String nodeVersion) {
    List<Probe> probes=new ArrayList<>(Arrays.asList(
      networkProbe("network-apt-backup","Ubuntu ARM64 备用国内源索引",RuntimeSources.APT_MIRRORS[1]+"dists/noble/InRelease"),
      networkProbe("network-node","Node.js 国内主镜像",RuntimeSources.NODE_MIRRORS[0]+nodeVersion+"/SHASUMS256.txt"),
      networkProbe("network-node-backup","Node.js 国内备用镜像",RuntimeSources.NODE_MIRRORS[1]+nodeVersion+"/SHASUMS256.txt"),
      networkProbe("network-npm","npm 国内源 DNS、TLS 与 HTTPS",RuntimeSources.NPM)
    ));
    if(!aptMirror.isEmpty())probes.add(0,networkProbe("network-apt","当前 Ubuntu ARM64 源索引",aptMirror+"dists/noble/InRelease"));
    return probes;
  }
  static String reason(String id,Throwable error) {
    String text=String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
    if(text.contains("could not resolve")||text.contains("temporary failure in name resolution"))return "DNS 解析失败；检查当前网络与 DNS 设置";
    if(text.contains("certificate")||text.contains("ssl peer")||text.contains("curl: (60)"))return "TLS 证书校验失败；检查系统时间、CA 证书和网络代理";
    if(text.contains("curl: (7)")||text.contains("network is unreachable")||text.contains("connection refused"))return "目标服务器连接失败；检查网络、代理与防火墙";
    if(text.contains("curl: (28)"))return "网络连接或传输超时；查看失败地址与 DNS、连接、TLS 耗时";
    if(text.contains("cannot link executable")||text.contains("error while loading shared libraries"))return "动态库或加载器缺失／不匹配；执行环境修复";
    if(text.contains("permission denied"))return "文件执行或目录访问被拒绝；检查执行权限及系统限制";
    if(text.contains("exec format")||text.contains("wrong elf")||text.contains("bad elf"))return "可执行文件架构或格式不匹配；需要 ARM64 环境";
    if(error instanceof RuntimeCommand.Failure && ((RuntimeCommand.Failure)error).timeout) {
      if(id.startsWith("python"))return "Python 运行未完成；已记录最后输出与可读取的进程状态，需要据此区分编译卡住、系统暂停或 PRoot 兼容问题";
      if(id.equals("node")||id.equals("npm"))return "Node.js／npm 运行未完成；查看同一步骤的进程状态与最后输出";
      return "步骤超时；查看同一步骤的进程状态与最后输出";
    }
    if(text.contains("no such file")||text.contains("not found"))return "程序或依赖文件缺失；执行环境修复";
    if(id.equals("dpkg"))return "软件包存在未完成的配置；执行修复以恢复配置，具体软件包见最后输出";
    return "工具执行异常；查看该检查项的命令、退出状态与最后输出";
  }
}
