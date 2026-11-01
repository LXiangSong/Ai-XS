package app.aixs.mobile;

/** Keep download, dpkg recovery, installation and validation independently observable. */
final class RuntimePackages {
  static final String PACKAGES="python3 git curl xz-utils ca-certificates";
  static final String APT="apt-get -o APT::Sandbox::User=root -o Acquire::Retries=1 -o Acquire::http::Timeout=20 "
      +"-o Acquire::https::Timeout=20 -o Acquire::Languages=none -o Dpkg::Use-Pty=0 ";
  static final String ENV="export DEBIAN_FRONTEND=noninteractive; ";
  static String recover(){return ENV+"echo 'AIXS_PROGRESS: 恢复上次中断的软件包配置'; dpkg --audit; dpkg --configure --pending";}
  static String update(){return ENV+"echo 'AIXS_PROGRESS: 更新 Ubuntu 软件源索引'; "+APT+"update --error-on=any";}
  static final String REINSTALL_PACKAGES=PACKAGES+" python3-minimal python3.12-minimal libpython3.12-minimal libpython3.12-stdlib";
  static String download(boolean reinstall){return ENV+"echo 'AIXS_PROGRESS: 下载 Python、Git、TLS 与下载工具依赖'; "+APT+"install --download-only "+(reinstall?"--reinstall ":"")+"-y --no-install-recommends "+(reinstall?REINSTALL_PACKAGES:PACKAGES);}
  static String install(){return ENV+"echo 'AIXS_PROGRESS: 解包与配置依赖（包含 Python 字节码编译）'; "+APT+"install --no-download -y --no-install-recommends "+PACKAGES;}
  static String reinstall(){return ENV+"echo 'AIXS_PROGRESS: 重新安装损坏的依赖文件'; "+APT+"install --no-download --reinstall -y --no-install-recommends "+REINSTALL_PACKAGES;}
}
