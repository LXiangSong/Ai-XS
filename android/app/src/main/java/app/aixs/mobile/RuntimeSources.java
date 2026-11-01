package app.aixs.mobile;

import java.util.regex.Pattern;

/** Download defaults for the shared ARM64 runtime. Keep upstream integrity checks enabled. */
final class RuntimeSources {
  // The minimal root has no CA bundle yet. APT still verifies Ubuntu's signed indexes and package hashes.
  static final String[] APT_MIRRORS={"http://mirrors.ustc.edu.cn/ubuntu-ports/", "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/"};
  static final String[] NODE_MIRRORS={"https://npmmirror.com/mirrors/node/", "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/"};
  static final String NPM="https://registry.npmmirror.com/";
  static final String FALLBACK_DNS="nameserver 223.5.5.5\nnameserver 119.29.29.29\n";
  private static final Pattern UBUNTU=Pattern.compile("https?://(?:ports\\.ubuntu\\.com/ubuntu-ports|(?:archive|security)\\.ubuntu\\.com/ubuntu|mirrors\\.(?:ustc\\.edu\\.cn|tuna\\.tsinghua\\.edu\\.cn)/ubuntu-ports)/?(?=\\s|$)");

  static String rewriteApt(String source,String mirror) {
    if(!java.util.Arrays.asList(APT_MIRRORS).contains(mirror))throw new IllegalArgumentException("未知的 Ubuntu ARM64 镜像");
    StringBuilder out=new StringBuilder();
    String[] lines=source.split("\n",-1);
    for(int i=0;i<lines.length;i++) {
      String line=lines[i],trim=line.trim();
      if(trim.startsWith("URIs:")||trim.startsWith("deb ")||trim.startsWith("deb-src "))
        line=UBUNTU.matcher(line).replaceAll(java.util.regex.Matcher.quoteReplacement(mirror));
      if(i>0)out.append('\n');out.append(line);
    }
    return out.toString();
  }
  static String activeUbuntuUri(String source) {
    for(String line:source.split("\n")) {
      String trim=line.trim();
      if(trim.startsWith("URIs:")||trim.startsWith("deb ")||trim.startsWith("deb-src ")) {
        java.util.regex.Matcher match=UBUNTU.matcher(line);
        if(match.find()){String url=match.group();return url.endsWith("/")?url:url+"/";}
      }
    }
    return "";
  }
  static String nodeUrl(int mirror,String version) {
    if(!version.matches("v[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IllegalArgumentException("Node 版本无效");
    return NODE_MIRRORS[mirror]+version+"/node-"+version+"-linux-arm64.tar.xz";
  }
}
