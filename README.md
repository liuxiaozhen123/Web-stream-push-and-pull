# 1环境安装
环境：本机 windows系统 的 linux 子系统 WSL

Ubuntu版本: 20.04

安装docker

# 2运行代码
## 2.1 启动 Kurento Media Server(KMS)
安装docker 后可尝试：

docker run --rm -p 8888:8888 kurento/kurento-media-server:latest

一直出现错误：

Unable to find image 'kurento/kurento-media-server:latest' locally docker: Error response from daemon: Get "https://registry-1.docker.io/v2/": net/http: request canceled while waiting for connection (Client.Timeout exceeded while awaiting headers). See 'docker run --help'.

原因：网络问题

尝试将镜像保存在本地：

docker pull docker.1ms.run/kurento/kurento-media-server:7.0.0

docker run --rm -p 8888:8888 docker.1ms.run/kurento/kurento-media-server:7.0.0

如果端口号已经被占用，执行下面的命令：

sudo lsof -i :8888

docker ps   # 找到容器 ID

docker stop <容器ID>

## 2.2启动Java Web(信令+静态页)

mvn clean package

java -Dkms.url=ws://localhost:8888/kurento -jar target/webrtc-javaweb.jar

Java 后端报错： Caused by: java.net.BindException: Address already in use

Linux: sudo lsof -i :8080 kill -9 PID

控制台应打印：

HTTP : http://localhost:8080

WS   : ws://localhost:8080/ws

# 3项目结构
```text
webrtc-javaweb-server/
├─ pom.xml
└─ src/main/
   ├─ java/com/example/webrtc/
   │   ├─ Main.java
   │   └─ KurentoSignalingEndpoint.java
   └─ webapp/
       ├─ index.html
       ├─ publish.html
       └─ play.html|
```
    
# 4常见问题
## 4.1 publish.html 无法打开
在本地跑时，防火墙打开使得拉流端拉流失败，因此可以暂时关闭防火墙
## 4.2 其他问题自行百度，网上资料很多

# 5当前代码工作原理
Publisher 与 Viewer以KMS为中转先互相传输SDP,ICE构建通路，通路构建好后，推流端发送媒体流到流媒体服务器KMS,流媒体服务器再传输给拉流端

Publisher (推流端)        KMS (Kurento,流媒体服务器)               Viewer (拉流端)
      |                               |                           |
      | ---- SDP Offer ------> |                           |  (Publisher 发 Offer 给 KMS)
      | <--- SDP Answer --->|                           |  (KMS 返回 Answer 给 Publisher)
      |                               |                           |
      | <- ICE Candidate -->|                           |  (KMS 给 Publisher ICE)
      | -- ICE Candidate --->|                           |  (Publisher 给 KMS ICE)
      |                               |                           |
      |======== 媒体流上行 =====>          |
      |                               |                           |
      |                               | <---- SDP Offer -- |  (Viewer 发 Offer 给 KMS)
      |                               | -- SDP Answer -> |  (KMS 返回 Answer 给 Viewer)
      |                               |                           |
      |                               | <- ICE Candidate  |  (Viewer 给 KMS ICE)
      |                               | --ICE Candidate > |  (KMS 给 Viewer ICE)
      |                               |                           |
      |---- publisherEp.connect(viewerEp) ----             |  (KMS 内部打通 发布者 → 观众)
      |                                |                           |
      |                                | ====== 媒体流下行 ======> |  (发布端视频流转发给观众)
      |                                |                           |


