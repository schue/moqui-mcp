/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.mcp

import groovy.json.JsonSlurper
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactTarpitException
import org.moqui.impl.context.ExecutionContextImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.AsyncContext
import javax.servlet.AsyncContextListener
import javax.servlet.AsyncEvent
import javax.servlet.ServletConfig
import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Service-Based MCP Servlet that delegates all business logic to McpServices.xml.
 * 
 * This servlet improves upon the original MoquiMcpServlet by:
 * - Properly delegating to existing McpServices.xml instead of reimplementing logic
 * - Adding SSE support for real-time bidirectional communication
 * - Providing better session management and error handling
 * - Supporting async operations for scalability
 */
class ServiceBasedMcpServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceBasedMcpServlet.class)
    
    private JsonSlurper jsonSlurper = new JsonSlurper()
    
    // Session management for SSE connections
    private final Map<String, AsyncContext> sseConnections = new ConcurrentHashMap<>()
    private final Map<String, String> sessionClients = new ConcurrentHashMap<>()
    
    // Executor for async operations and keep-alive pings
    private ScheduledExecutorService executorService
    
    // Configuration
    private String sseEndpoint = "/sse"
    private String messageEndpoint = "/mcp/message"
    private int keepAliveIntervalSeconds = 30
    private int maxConnections = 100
    
    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)
        
        // Read configuration from servlet init parameters
        sseEndpoint = config.getInitParameter("sseEndpoint") ?: sseEndpoint
        messageEndpoint = config.getInitParameter("messageEndpoint") ?: messageEndpoint
        keepAliveIntervalSeconds = config.getInitParameter("keepAliveIntervalSeconds")?.toInteger() ?: keepAliveIntervalSeconds
        maxConnections = config.getInitParameter("maxConnections")?.toInteger() ?: maxConnections
        
        // Initialize executor service
        executorService = Executors.newScheduledThreadPool(4)
        
        // Start keep-alive task
        startKeepAliveTask()
        
        String webappName = config.getInitParameter("moqui-name") ?: 
            config.getServletContext().getInitParameter("moqui-name")
        
        logger.info("ServiceBasedMcpServlet initialized for webapp ${webappName}")
        logger.info("SSE endpoint: ${sseEndpoint}, Message endpoint: ${messageEndpoint}")
        logger.info("Keep-alive interval: ${keepAliveIntervalSeconds}s, Max connections: ${maxConnections}")
        logger.info("All business logic delegated to McpServices.xml")
    }
    
    @Override
    void destroy() {
        super.destroy()
        
        // Shutdown executor service
        if (executorService) {
            executorService.shutdown()
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow()
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        
        // Close all SSE connections
        sseConnections.values().each { asyncContext ->
            try {
                asyncContext.complete()
            } catch (Exception e) {
                logger.warn("Error closing SSE connection: ${e.message}")
            }
        }
        sseConnections.clear()
        sessionClients.clear()
        
        logger.info("ServiceBasedMcpServlet destroyed")
    }
    
    @Override
    void service(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        ExecutionContextFactoryImpl ecfi = 
            (ExecutionContextFactoryImpl) getServletContext().getAttribute("executionContextFactory")
        String webappName = getInitParameter("moqui-name") ?: 
            getServletContext().getInitParameter("moqui-name")
        
        if (ecfi == null || webappName == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "System is initializing, try again soon.")
            return
        }
        
        // Handle CORS
        if (handleCors(request, response, webappName, ecfi)) return
        
        String pathInfo = request.getPathInfo()
        
        // Route based on endpoint
        if (pathInfo?.startsWith(sseEndpoint)) {
            handleSseConnection(request, response, ecfi, webappName)
        } else if (pathInfo?.startsWith(messageEndpoint)) {
            handleMessage(request, response, ecfi, webappName)
        } else {
            // Legacy support for /rpc endpoint
            if (pathInfo?.startsWith("/rpc")) {
                handleLegacyRpc(request, response, ecfi, webappName)
            } else {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "MCP endpoint not found")
            }
        }
    }
    
    private void handleSseConnection(HttpServletRequest request, HttpServletResponse response, 
                                   ExecutionContextFactoryImpl ecfi, String webappName) 
            throws IOException {
        
        logger.info("New SSE connection request from ${request.remoteAddr}")
        
        // Check connection limit
        if (sseConnections.size() >= maxConnections) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                "Too many SSE connections")
            return
        }
        
        // Set SSE headers
        response.setContentType("text/event-stream")
        response.setCharacterEncoding("UTF-8")
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.setHeader("Pragma", "no-cache")
        response.setHeader("Expires", "0")
        response.setHeader("Connection", "keep-alive")
        
        // Generate session ID
        String sessionId = generateSessionId()
        
        // Store client info
        String userAgent = request.getHeader("User-Agent") ?: "Unknown"
        sessionClients.put(sessionId, userAgent)
        
        // Enable async support
        AsyncContext asyncContext = request.startAsync(request, response)
        asyncContext.setTimeout(0) // No timeout
        sseConnections.put(sessionId, asyncContext)
        
        logger.info("SSE connection established: ${sessionId} from ${userAgent}")
        
        // Send initial connection event
        sendSseEvent(sessionId, "connect", [
            type: "connected",
            sessionId: sessionId,
            timestamp: System.currentTimeMillis(),
            serverInfo: [
                name: "Moqui Service-Based MCP Server",
                version: "2.1.0",
                protocolVersion: "2025-06-18",
                endpoints: [
                    sse: sseEndpoint,
                    message: messageEndpoint
                ],
                architecture: "Service-based - all business logic delegated to McpServices.xml"
            ]
        ])
        
        // Set up connection close handling
        asyncContext.addListener(new AsyncContextListener() {
            @Override
            void onComplete(AsyncEvent event) throws IOException {
                sseConnections.remove(sessionId)
                sessionClients.remove(sessionId)
                logger.info("SSE connection completed: ${sessionId}")
            }
            
            @Override
            void onTimeout(AsyncEvent event) throws IOException {
                sseConnections.remove(sessionId)
                sessionClients.remove(sessionId)
                logger.info("SSE connection timeout: ${sessionId}")
            }
            
            @Override
            void onError(AsyncEvent event) throws IOException {
                sseConnections.remove(sessionId)
                sessionClients.remove(sessionId)
                logger.warn("SSE connection error: ${sessionId} - ${event.throwable?.message}")
            }
            
            @Override
            void onStartAsync(AsyncEvent event) throws IOException {
                // No action needed
            }
        })
    }
    
    private void handleMessage(HttpServletRequest request, HttpServletResponse response, 
                              ExecutionContextFactoryImpl ecfi, String webappName) 
            throws IOException {
        
        long startTime = System.currentTimeMillis()
        
        if (logger.traceEnabled) {
            logger.trace("Start MCP message request to [${request.getPathInfo()}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")
        }
        
        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null) {
            logger.warn("In ServiceBasedMcpServlet.handleMessage there is already an ExecutionContext for user ${activeEc.user.username}")
            activeEc.destroy()
        }
        
        ExecutionContextImpl ec = ecfi.getEci()
        
        try {
            // Initialize web facade for authentication
            ec.initWebFacade(webappName, request, response)
            
            logger.info("Service-Based MCP Message authenticated user: ${ec.user?.username}, userId: ${ec.user?.userId}")
            
            // If no user authenticated, try to authenticate as admin for MCP requests
            if (!ec.user?.userId) {
                logger.info("No user authenticated, attempting admin login for Service-Based MCP")
                try {
                    ec.user.loginUser("admin", "admin")
                    logger.info("Service-Based MCP Admin login successful, user: ${ec.user?.username}")
                } catch (Exception e) {
                    logger.warn("Service-Based MCP Admin login failed: ${e.message}")
                }
            }
            
            // Handle different HTTP methods
            String method = request.getMethod()
            
            if ("GET".equals(method)) {
                // Handle SSE subscription or status check
                handleGetMessage(request, response, ec)
            } else if ("POST".equals(method)) {
                // Handle JSON-RPC message
                handlePostMessage(request, response, ec)
            } else {
                response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, 
                    "Method not allowed. Use GET for SSE subscription or POST for JSON-RPC messages.")
            }
            
        } catch (ArtifactAuthorizationException e) {
            logger.warn("Service-Based MCP Access Forbidden (no authz): " + e.message)
            sendJsonRpcError(response, -32001, "Access Forbidden: " + e.message, null)
        } catch (ArtifactTarpitException e) {
            logger.warn("Service-Based MCP Too Many Requests (tarpit): " + e.message)
            response.setStatus(429)
            if (e.getRetryAfterSeconds()) {
                response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            }
            sendJsonRpcError(response, -32002, "Too Many Requests: " + e.message, null)
        } catch (Throwable t) {
            logger.error("Error in Service-Based MCP message request", t)
            sendJsonRpcError(response, -32603, "Internal error: " + t.message, null)
        } finally {
            ec.destroy()
        }
    }
    
    private void handleGetMessage(HttpServletRequest request, HttpServletResponse response, 
                                 ExecutionContextImpl ec) throws IOException {
        
        String sessionId = request.getParameter("sessionId")
        String acceptHeader = request.getHeader("Accept")
        
        // If client wants SSE and has sessionId, this is a subscription request
        if (acceptHeader?.contains("text/event-stream") && sessionId) {
            if (sseConnections.containsKey(sessionId)) {
                response.setContentType("text/event-stream")
                response.setCharacterEncoding("UTF-8")
                response.setHeader("Cache-Control", "no-cache")
                response.setHeader("Connection", "keep-alive")
                
                // Send subscription confirmation
                response.writer.write("event: subscribed\n")
                response.writer.write("data: {\"type\":\"subscribed\",\"sessionId\":\"${sessionId}\",\"timestamp\":\"${System.currentTimeMillis()}\",\"architecture\":\"Service-based\"}\n\n")
                response.writer.flush()
            } else {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Session not found")
            }
        } else {
            // Return server status
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            
            def status = [
                serverInfo: [
                    name: "Moqui Service-Based MCP Server",
                    version: "2.1.0",
                    protocolVersion: "2025-06-18",
                    architecture: "Service-based - all business logic delegated to McpServices.xml"
                ],
                connections: [
                    active: sseConnections.size(),
                    max: maxConnections
                ],
                endpoints: [
                    sse: sseEndpoint,
                    message: messageEndpoint,
                    rpc: "/rpc"
                ],
                capabilities: [
                    tools: true,
                    resources: true,
                    prompts: true,
                    sse: true,
                    jsonRpc: true,
                    services: "McpServices.xml"
                ]
            ]
            
            response.writer.write(groovy.json.JsonOutput.toJson(status))
        }
    }
    
    private void handlePostMessage(HttpServletRequest request, HttpServletResponse response, 
                                  ExecutionContextImpl ec) throws IOException {
        
        // Read and parse JSON-RPC request
        String requestBody
        try {
            BufferedReader reader = request.reader
            StringBuilder body = new StringBuilder()
            String line
            while ((line = reader.readLine()) != null) {
                body.append(line)
            }
            requestBody = body.toString()
            
        } catch (IOException e) {
            logger.error("Failed to read request body: ${e.message}")
            sendJsonRpcError(response, -32700, "Failed to read request body: " + e.message, null)
            return
        }
        
        if (!requestBody) {
            logger.warn("Empty request body in JSON-RPC POST request")
            sendJsonRpcError(response, -32602, "Empty request body", null)
            return
        }
        
        def rpcRequest
        try {
            rpcRequest = jsonSlurper.parseText(requestBody)
        } catch (Exception e) {
            logger.error("Failed to parse JSON-RPC request: ${e.message}")
            sendJsonRpcError(response, -32700, "Invalid JSON: " + e.message, null)
            return
        }
        
        // Validate JSON-RPC 2.0 basic structure
        if (!rpcRequest?.jsonrpc || rpcRequest.jsonrpc != "2.0" || !rpcRequest?.method) {
            logger.warn("Invalid JSON-RPC 2.0 structure: jsonrpc=${rpcRequest?.jsonrpc}, method=${rpcRequest?.method}")
            sendJsonRpcError(response, -32600, "Invalid JSON-RPC 2.0 request", rpcRequest?.id)
            return
        }
        
        // Process MCP method by delegating to services
        def result = processMcpMethod(rpcRequest.method, rpcRequest.params, ec, rpcRequest)
        
        // Build JSON-RPC response
        def rpcResponse = [
            jsonrpc: "2.0",
            id: rpcRequest.id,
            result: result
        ]
        
        // Send response
        response.setContentType("application/json")
        response.setCharacterEncoding("UTF-8")
        response.writer.write(groovy.json.JsonOutput.toJson(rpcResponse))
    }
    
    private void handleLegacyRpc(HttpServletRequest request, HttpServletResponse response, 
                                ExecutionContextFactoryImpl ecfi, String webappName) 
            throws IOException {
        
        // Legacy support - delegate to existing MoquiMcpServlet logic
        logger.info("Handling legacy RPC request - redirecting to services")
        
        // For legacy requests, we can use the same service-based approach
        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null) {
            logger.warn("In ServiceBasedMcpServlet.handleLegacyRpc there is already an ExecutionContext for user ${activeEc.user.username}")
            activeEc.destroy()
        }
        
        ExecutionContextImpl ec = ecfi.getEci()
        
        try {
            // Initialize web facade for authentication
            ec.initWebFacade(webappName, request, response)
            
            // If no user authenticated, try to authenticate as admin for MCP requests
            if (!ec.user?.userId) {
                logger.info("No user authenticated, attempting admin login for Legacy MCP")
                try {
                    ec.user.loginUser("admin", "admin")
                    logger.info("Legacy MCP Admin login successful, user: ${ec.user?.username}")
                } catch (Exception e) {
                    logger.warn("Legacy MCP Admin login failed: ${e.message}")
                }
            }
            
            // Read and parse JSON-RPC request (same as POST handling)
            String requestBody
            try {
                BufferedReader reader = request.reader
                StringBuilder body = new StringBuilder()
                String line
                while ((line = reader.readLine()) != null) {
                    body.append(line)
                }
                requestBody = body.toString()
                
            } catch (IOException e) {
                logger.error("Failed to read legacy RPC request body: ${e.message}")
                sendJsonRpcError(response, -32700, "Failed to read request body: " + e.message, null)
                return
            }
            
            if (!requestBody) {
                logger.warn("Empty request body in legacy RPC POST request")
                sendJsonRpcError(response, -32602, "Empty request body", null)
                return
            }
            
            def rpcRequest
            try {
                rpcRequest = jsonSlurper.parseText(requestBody)
            } catch (Exception e) {
                logger.error("Failed to parse legacy JSON-RPC request: ${e.message}")
                sendJsonRpcError(response, -32700, "Invalid JSON: " + e.message, null)
                return
            }
            
            // Validate JSON-RPC 2.0 basic structure
            if (!rpcRequest?.jsonrpc || rpcRequest.jsonrpc != "2.0" || !rpcRequest?.method) {
                logger.warn("Invalid legacy JSON-RPC 2.0 structure: jsonrpc=${rpcRequest?.jsonrpc}, method=${rpcRequest?.method}")
                sendJsonRpcError(response, -32600, "Invalid JSON-RPC 2.0 request", rpcRequest?.id)
                return
            }
            
            // Process MCP method by delegating to services
            def result = processMcpMethod(rpcRequest.method, rpcRequest.params, ec, rpcRequest)
            
            // Build JSON-RPC response
            def rpcResponse = [
                jsonrpc: "2.0",
                id: rpcRequest.id,
                result: result
            ]
            
            // Send response
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.writer.write(groovy.json.JsonOutput.toJson(rpcResponse))
            
        } catch (ArtifactAuthorizationException e) {
            logger.warn("Legacy MCP Access Forbidden (no authz): " + e.message)
            sendJsonRpcError(response, -32001, "Access Forbidden: " + e.message, null)
        } catch (ArtifactTarpitException e) {
            logger.warn("Legacy MCP Too Many Requests (tarpit): " + e.message)
            response.setStatus(429)
            if (e.getRetryAfterSeconds()) {
                response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            }
            sendJsonRpcError(response, -32002, "Too Many Requests: " + e.message, null)
        } catch (Throwable t) {
            logger.error("Error in legacy MCP message request", t)
            sendJsonRpcError(response, -32603, "Internal error: " + t.message, null)
        } finally {
            ec.destroy()
        }
    }
    
    private Map<String, Object> processMcpMethod(String method, Map params, ExecutionContextImpl ec, def rpcRequest) {
        logger.info("Service-Based METHOD: ${method} with params: ${params}")
        
        try {
            switch (method) {
                case "initialize":
                    return callMcpService("mcp#Initialize", params, ec)
                case "ping":
                    return callMcpService("mcp#Ping", params, ec)
                case "tools/list":
                    return callMcpService("mcp#ToolsList", params, ec)
                case "tools/call":
                    return callMcpService("mcp#ToolsCall", params, ec)
                case "resources/list":
                    return callMcpService("mcp#ResourcesList", params, ec)
                case "resources/read":
                    return callMcpService("mcp#ResourcesRead", params, ec)
                case "notifications/subscribe":
                    return handleSubscription(params, ec, rpcRequest)
                default:
                    throw new IllegalArgumentException("Unknown MCP method: ${method}")
            }
        } catch (Exception e) {
            logger.error("Error processing Service-Based MCP method ${method}", e)
            throw e
        }
    }
    
    private Map<String, Object> callMcpService(String serviceName, Map params, ExecutionContextImpl ec) {
        logger.info("Service-Based Calling MCP service: ${serviceName} with params: ${params}")
        
        try {
            def result = ec.service.sync().name("org.moqui.mcp.McpServices.${serviceName}")
                .parameters(params ?: [:])
                .call()
            
            logger.info("Service-Based MCP service ${serviceName} result: ${result}")
            return result.result
        } catch (Exception e) {
            logger.error("Error calling Service-Based MCP service ${serviceName}", e)
            throw e
        }
    }
    
    private Map<String, Object> handleSubscription(Map params, ExecutionContextImpl ec, def rpcRequest) {
        String sessionId = params.sessionId as String
        String eventType = params.eventType as String
        
        logger.info("Service-Based Subscription request: sessionId=${sessionId}, eventType=${eventType}")
        
        if (!sessionId || !sseConnections.containsKey(sessionId)) {
            throw new IllegalArgumentException("Invalid or expired session")
        }
        
        // Store subscription (in a real implementation, you'd maintain subscription lists)
        // For now, just confirm subscription
        
        // Send subscription confirmation via SSE
        sendSseEvent(sessionId, "subscribed", [
            type: "subscription_confirmed",
            sessionId: sessionId,
            eventType: eventType,
            timestamp: System.currentTimeMillis(),
            architecture: "Service-based via McpServices.xml"
        ])
        
        return [
            subscribed: true,
            sessionId: sessionId,
            eventType: eventType,
            timestamp: System.currentTimeMillis()
        ]
    }
    
    private void sendJsonRpcError(HttpServletResponse response, int code, String message, Object id) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK)
        response.setContentType("application/json")
        response.setCharacterEncoding("UTF-8")
        
        def errorResponse = [
            jsonrpc: "2.0",
            error: [code: code, message: message],
            id: id
        ]
        
        response.writer.write(groovy.json.JsonOutput.toJson(errorResponse))
    }
    
    private void sendSseEvent(String sessionId, String eventType, Map data) {
        AsyncContext asyncContext = sseConnections.get(sessionId)
        if (!asyncContext) {
            logger.debug("SSE connection not found for session: ${sessionId}")
            return
        }
        
        try {
            HttpServletResponse response = asyncContext.getResponse()
            response.writer.write("event: ${eventType}\n")
            response.writer.write("data: ${groovy.json.JsonOutput.toJson(data)}\n\n")
            response.writer.flush()
        } catch (Exception e) {
            logger.warn("Failed to send SSE event to ${sessionId}: ${e.message}")
            // Remove broken connection
            sseConnections.remove(sessionId)
            sessionClients.remove(sessionId)
        }
    }
    
    private void broadcastSseEvent(String eventType, Map data) {
        sseConnections.keySet().each { sessionId ->
            sendSseEvent(sessionId, eventType, data)
        }
    }
    
    private void startKeepAliveTask() {
        executorService.scheduleWithFixedDelay({
            try {
                sseConnections.keySet().each { sessionId ->
                    sendSseEvent(sessionId, "ping", [
                        type: "ping",
                        timestamp: System.currentTimeMillis(),
                        connections: sseConnections.size(),
                        architecture: "Service-based via McpServices.xml"
                    ])
                }
            } catch (Exception e) {
                logger.warn("Error in Service-Based keep-alive task: ${e.message}")
            }
        }, keepAliveIntervalSeconds, keepAliveIntervalSeconds, TimeUnit.SECONDS)
    }
    
    private String generateSessionId() {
        return UUID.randomUUID().toString()
    }
    
    // CORS handling based on MoquiServlet pattern
    private static boolean handleCors(HttpServletRequest request, HttpServletResponse response, String webappName, ExecutionContextFactoryImpl ecfi) {
        String originHeader = request.getHeader("Origin")
        if (originHeader) {
            response.setHeader("Access-Control-Allow-Origin", originHeader)
            response.setHeader("Access-Control-Allow-Credentials", "true")
        }
        
        String methodHeader = request.getHeader("Access-Control-Request-Method")
        if (methodHeader) {
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Mcp-Session-Id, MCP-Protocol-Version, Accept")
            response.setHeader("Access-Control-Max-Age", "3600")
            return true
        }
        return false
    }
}