package com.example.webrtc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.kurento.client.*;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /ws
 * 消息协议（JSON）：
 *  - 发布端：
 *      { "id":"publish", "sdpOffer": "<offer>" }
 *      { "id":"onIceCandidate", "candidate": { "candidate":"...", "sdpMid":"...", "sdpMLineIndex":0 } }
 *  - 播放端：
 *      { "id":"play", "sdpOffer": "<offer>" }
 *      { "id":"onIceCandidate", "candidate": {...} }
 *  - 停止：
 *      { "id":"stop" }
 *  - 服务器下行：
 *      { "id":"publishResponse", "sdpAnswer":"..." }
 *      { "id":"playResponse",    "sdpAnswer":"..." }
 *      { "id":"iceCandidate",    "candidate": {...} }
 *      { "id":"error", "message":"..." }
 */
@ServerEndpoint("/ws")
public class KurentoSignalingEndpoint {

    private static final Gson GSON = new Gson();

    // 全局 KMS 连接（本地默认 ws://localhost:8888/kurento）
    private static final KurentoClient KMS = KurentoClient.create(System.getProperty(
            "kms.url", "ws://localhost:8888/kurento")); // localhost 192.168.172.182

    // 简单“单频道”实现：一个发布者，多观众
    private static MediaPipeline pipeline;
    private static WebRtcEndpoint publisherEp; // 既负责理解SDP/ICE,又负责接收/转发真正的 音视频RTC包
    private static Session publisherSession;  // 只处理信令 JSON(SDP/ICE)，不参与媒体传输

    // 观众：session -> viewerEndpoint
    private static final Map<Session, WebRtcEndpoint> viewers = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session) {
        // 可记录鉴权或日志
    }

    // 当服务器接收到某个客户端的消息时，会调用下面这个函数
    @OnMessage
    public void onMessage(Session session, String message) {
        try {
            JsonObject msg = GSON.fromJson(message, JsonObject.class);
            String id = msg.get("id").getAsString();

            switch (id) {
                case "publish": // 处理发布请求
                    handlePublish(session, msg);
                    break;
                case "play":   // 处理播放请求
                    handlePlay(session, msg);
                    break;
                case "onIceCandidate":  // 处理连接建立所需要的网络信息 ICE 候选集是WebRTC 连接时收集的所有可用网络地址
                    handleIceCandidate(session, msg.getAsJsonObject("candidate"));
                    break;
                case "stop":  //  停止推流
                    handleStop(session);
                    break;
                case "ping":  // 心跳检测
                    JsonObject pong = new JsonObject();
                    pong.addProperty("id", "pong");
                    send(session, pong.toString());
                    break;
                default: // 如果id不认识，则返回错误信息
                    sendError(session, "Unknown id: " + id);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendError(session, "Exception: " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        handleStop(session);
    }

    @OnError
    public void onError(Session session, Throwable thr) {
        thr.printStackTrace();
    }

    /* ------------------- handlers ------------------- */

    private synchronized void handlePublish(Session session, JsonObject msg) {
        try {
            String sdpOffer = msg.get("sdpOffer").getAsString();  // 从前端传来的消息里提取sdpOffer

            if (pipeline == null) {
                pipeline = KMS.createMediaPipeline();
            }

            // 如果已有发布者，替换旧发布者
            releasePublisher();
            
            // 用于和浏览器中的 RTCPeerConnection 建立媒体通道
            publisherEp = new WebRtcEndpoint.Builder(pipeline).build();
            publisherSession = session;

            // 当 Kurento 发现候选地址 （ICE candidate）时，通过 WebSocket 发给前端浏览器：KMS 服务器->浏览器 发送ICE
            publisherEp.addIceCandidateFoundListener(ev ->
                    sendIceCandidate(publisherSession, ev.getCandidate()));

            // 处理浏览器的 SDP offer
            String sdpAnswer = publisherEp.processOffer(sdpOffer);
            sendJson(session, "publishResponse", "sdpAnswer", sdpAnswer);

            publisherEp.gatherCandidates();

        } catch (Throwable t) {
            t.printStackTrace();
            sendError(session, "Publish failed: " + t.getMessage());
        }
    }

    private synchronized void handlePlay(Session session, JsonObject msg) {  // msg:前端传过来的JSON消息  浏览器将 SDP offer/ 候选地址 ICE 传递给 session, 后端传输给 KMS, 再返回 answer/对端的ICE候选 给 此session
        try {
            if (publisherEp == null) {
                sendError(session, "No publisher");
                return;
            }
            String sdpOffer = msg.get("sdpOffer").getAsString();

            WebRtcEndpoint viewerEp = new WebRtcEndpoint.Builder(pipeline).build(); // 创建观众端点
            viewers.put(session, viewerEp);  // 观看端浏览器 收到SDP Answer并设置成功 +  ICE 建立成功后 KMS 真正把发布端的视频流送到观众前端

            // // 🟢 设置观众端带宽范围，引导选择高清层
            // viewerEp.setMinVideoRecvBandwidth(1500);  // kbps，避免只收低清层
            // viewerEp.setMaxVideoRecvBandwidth(6000);  // kbps，允许最高 6Mbps
            // viewerEp.setMinVideoSendBandwidth(1500);
            // viewerEp.setMaxVideoSendBandwidth(6000);

            // 观众下行 ICE
            viewerEp.addIceCandidateFoundListener(ev ->
                    sendIceCandidate(session, ev.getCandidate()));

            // 连接：发布者 -> 观众
            publisherEp.connect(viewerEp);

            String sdpAnswer = viewerEp.processOffer(sdpOffer);
            sendJson(session, "playResponse", "sdpAnswer", sdpAnswer);

            viewerEp.gatherCandidates();

        } catch (Throwable t) {
            t.printStackTrace();
            sendError(session, "Play failed: " + t.getMessage());
        }
    }

    private synchronized void handleIceCandidate(Session session, JsonObject cand) {
        IceCandidate candidate = new IceCandidate(
                cand.get("candidate").getAsString(),
                cand.get("sdpMid").getAsString(),
                cand.get("sdpMLineIndex").getAsInt());

        if (session.equals(publisherSession) && publisherEp != null) {
            publisherEp.addIceCandidate(candidate);
            return;
        }
        WebRtcEndpoint viewerEp = viewers.get(session);
        if (viewerEp != null) {
            viewerEp.addIceCandidate(candidate);
        }
    }

    private synchronized void handleStop(Session session) {
        // 如果是发布者，释放整个频道
        if (session.equals(publisherSession)) {
            releaseAll();
            return;
        }
        // 如果是观众，仅释放自己的端点
        WebRtcEndpoint v = viewers.remove(session);
        if (v != null) {
            try { v.release(); } catch (Exception ignored) {}
        }
    }

    /* ------------------- utils ------------------- */

    private void sendJson(Session session, String id, String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty(key, value);
        send(session, o.toString());
    }

    private void sendIceCandidate(Session session, IceCandidate c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", "iceCandidate");
        JsonObject cand = new JsonObject();
        cand.addProperty("candidate", c.getCandidate());
        cand.addProperty("sdpMid", c.getSdpMid());
        cand.addProperty("sdpMLineIndex", c.getSdpMLineIndex());
        o.add("candidate", cand);
        send(session, o.toString());
    }

    private void sendError(Session session, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("id", "error");
        o.addProperty("message", msg);
        send(session, o.toString());
    }

    // private void send(Session session, String text) {
    //     try {
    //         if (session.isOpen()) session.getBasicRemote().sendText(text);
    //     } catch (IOException ignored) {}
    // }
    // 将send（）方法改成完全异步版本，避免ICE 在拉流端发生阻塞 导致 拉流端无法建立链路
    private void send(Session session, String text) {
        try {
            if (session != null && session.isOpen()) {
                session.getAsyncRemote().sendText(text, result -> {
                    if (!result.isOK()) {
                        System.err.println("[WebSocket] 异步发送失败: " + result.getException());
                    } else {
                        System.out.println("[WebSocket] 异步发送成功: " + text);
                    }
                });
            } else {
                System.err.println("[WebSocket] Session 已关闭或为空，无法发送: " + text);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private synchronized void releasePublisher() {
        if (publisherEp != null) {
            try { publisherEp.release(); } catch (Exception ignored) {}
            publisherEp = null;
            publisherSession = null;
        }
    }

    private synchronized void releaseAll() {
        // 先释放观众
        for (Map.Entry<Session, WebRtcEndpoint> e : viewers.entrySet()) {
            try { e.getValue().release(); } catch (Exception ignored) {}
        }
        viewers.clear();

        releasePublisher();

        if (pipeline != null) {
            try { pipeline.release(); } catch (Exception ignored) {}
            pipeline = null;
        }
    }
}
