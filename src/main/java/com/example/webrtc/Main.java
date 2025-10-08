package com.example.webrtc;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;

import javax.websocket.server.ServerContainer;

import java.net.InetSocketAddress;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        // 1) HTTP 服务器
        // Server server = new Server(8080);
        Server server = new Server(new InetSocketAddress("0.0.0.0", 8080));


        // 2) Web 应用上下文
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS); // session:指HTTP会话，它是服务器端用来 在多个请求之间保持用户状态的机制
        context.setContextPath("/");
        server.setHandler(context);

        // 开发期从源码目录提供静态文件；运行请从项目根目录启动
        String webroot = Path.of("src", "main", "webapp").toAbsolutePath().toString();
        context.setResourceBase(webroot);
        context.setWelcomeFiles(new String[]{"index.html"});

        // 3) 静态资源 DefaultServlet
        ServletHolder defaultServlet = new ServletHolder("default", DefaultServlet.class); //  创建一个 叫 default 的静态资源处理Servlet
        defaultServlet.setInitParameter("dirAllowed", "true");
        context.addServlet(defaultServlet, "/");

        

        // 4) 初始化 JSR-356 容器并注册端点 /ws
        // WebSocketServerContainerInitializer
        //         .configureContext(context)
        //         .addEndpoint(KurentoSignalingEndpoint.class);
        // 初始化 JSR-356 容器
        // ServerContainer wscontainer =
        // (ServerContainer) context.getServletContext()
        //         .getAttribute("javax.websocket.server.ServerContainer");
        ServerContainer wscontainer = WebSocketServerContainerInitializer.configureContext(context);  // 在Jetty 的Web 应用 context中启用 WebSocket 支持
        wscontainer.addEndpoint(KurentoSignalingEndpoint.class);  // 注册一个WebSocket 端点，让客户端通过WebSocket 连接到服务器时，进入KurentoSignalingEndpoint 的逻辑

        // 注册端点
        
        System.out.println("context = " + context);
        System.out.println("servletContext = " + (context == null ? "null" : context.getServletContext()));
        System.out.println("wscontainer = " + context.getServletContext().getAttribute("javax.websocket.server.ServerContainer"));
        wscontainer.addEndpoint(KurentoSignalingEndpoint.class);

        server.setHandler(context);
        server.start();

        System.out.println("HTTP  : http://localhost:8080");
        System.out.println("WS    : ws://localhost:8080/ws");
        server.join();
    }
}
