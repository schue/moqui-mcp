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
import groovy.json.JsonOutput
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactTarpitException
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.AsyncContext
import javax.servlet.AsyncListener
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
 * - Using Visit-based persistence for session management
 */
class ServiceBasedMcpServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceBasedMcpServlet.class)
    
    private JsonSlurper jsonSlurper = new JsonSlurper()
    
    // Session management using Visit-based persistence
    private final Map<String, VisitBasedMcpSession> activeSessions = new ConcurrentHashMap<>()
    
    // Executor for async operations and keep-alive pings
    private ScheduledExecutorService executorService
    
    // Configuration
    private String sseEndpoint = "/sse"
    private String messageEndpoint = "/message"
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
        
        // Close all active sessions
        activeSessions.values().each { session ->
            try {
                session.closeGracefully()
            } catch (Exception e) {
                logger.warn("Error closing MCP session: ${e.message}")
            }
        }
        activeSessions.clear()
        
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
        
        String requestURI = request.getRequestURI()
        String method = request.getMethod()
        
        logger.info("ServiceBasedMcpServlet routing: method=${method}, requestURI=${requestURI}, sseEndpoint=${sseEndpoint}, messageEndpoint=${messageEndpoint}")
        
        // Route based on HTTP method and URI pattern (like EnhancedMcpServlet)
        if ("GET".equals(method) && requestURI.endsWith("/sse")) {
            handleSseConnection(request, response, ecfi, webappName)
        } else if ("POST".equals(method) && requestURI.endsWith("/message")) {
            handleMessage(request, response, ecfi, webappName)
        } else if ("POST".equals(method) && (requestURI.equals("/mcp") || requestURI.endsWith("/mcp"))) {
            // Handle POST requests to /mcp for JSON-RPC
            handleLegacyRpc(request, response, ecfi, webappName)
        } else if ("GET".equals(method) && (requestURI.equals("/mcp") || requestURI.endsWith("/mcp"))) {
            // Handle GET requests to /mcp - SSE fallback for server info
            handleSseConnection(request, response, ecfi, webappName)
        } else {
            // Legacy support for /rpc endpoint
            if (requestURI.startsWith("/rpc")) {
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
        if (activeSessions.size() >= maxConnections) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                "Too many SSE connections")
            return
        }
        
        // Get ExecutionContext for this request
        ExecutionContextImpl ec = ecfi.getEci()
        
        // Initialize web facade to create Visit
        ec.initWebFacade(webappName, request, response)
        
        // Set SSE headers (matching EnhancedMcpServlet)
        response.setContentType("text/event-stream")
        response.setCharacterEncoding("UTF-8")
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("Access-Control-Allow-Origin", "*")
        response.setHeader("X-Accel-Buffering", "no") // Disable nginx buffering
        
        // Get or create Visit (Moqui automatically creates Visit)
        def visit = ec.user.getVisit()
        if (!visit) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to create Visit")
            return
        }
        
        // Create Visit-based session transport
        VisitBasedMcpSession session = new VisitBasedMcpSession(visit, response.writer, ec)
        activeSessions.put(visit.visitId, session)
        
        // Enable async support
        AsyncContext asyncContext = null
        if (request.isAsyncSupported()) {
            asyncContext = request.startAsync(request, response)
            asyncContext.setTimeout(0) // No timeout
            logger.info("Service-Based SSE async context created for session ${visit.visitId}")
        } else {
            logger.warn("Service-Based SSE async not supported, falling back to blocking mode for session ${visit.visitId}")
        }
        
        logger.info("Service-Based SSE connection established: ${visit.visitId} from ${request.remoteAddr}")
        
        // Send initial connection event (matching EnhancedMcpServlet format)
        def connectData = [
            type: "connected",
            sessionId: visit.visitId,
            timestamp: System.currentTimeMillis(),
            serverInfo: [
                name: "Moqui Service-Based MCP Server",
                version: "2.1.0",
                protocolVersion: "2025-06-18",
                architecture: "Service-based with Visit persistence"
            ]
        ]
        sendSseEvent(response.writer, "connect", groovy.json.JsonOutput.toJson(connectData), 0)
        
        // Send endpoint info for message posting
        sendSseEvent(response.writer, "endpoint", "/mcp/message?sessionId=" + visit.visitId, 1)
        
        // Set up connection close handling
        asyncContext.addListener(new AsyncListener() {
            @Override
            void onComplete(AsyncEvent event) throws IOException {
                activeSessions.remove(visit.visitId)
                session.close()
                logger.info("Service-Based SSE connection completed: ${visit.visitId}")
            }
            
            @Override
            void onTimeout(AsyncEvent event) throws IOException {
                activeSessions.remove(visit.visitId)
                session.close()
                logger.info("Service-Based SSE connection timeout: ${visit.visitId}")
            }
            
            @Override
            void onError(AsyncEvent event) throws IOException {
                activeSessions.remove(visit.visitId)
                session.close()
                logger.warn("Service-Based SSE connection error: ${visit.visitId} - ${event.throwable?.message}")
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
            
            // Require authentication - do not fallback to admin
            if (!ec.user?.userId) {
                logger.warn("Service-Based MCP Request denied - no authenticated user")
                // Handle error directly without sendError to avoid Moqui error screen interference
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
                response.setContentType("application/json")
                response.writer.write(groovy.json.JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32000, message: "Authentication required. Please provide valid credentials."],
                    id: null
                ]))
                return
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
            // Get Visit directly - this is our session (like EnhancedMcpServlet)
            def visit = ec.entity.find("moqui.server.Visit")
                .condition("visitId", sessionId)
                .one()
            
            if (visit) {
                response.setContentType("text/event-stream")
                response.setCharacterEncoding("UTF-8")
                response.setHeader("Cache-Control", "no-cache")
                response.setHeader("Connection", "keep-alive")
                
                // Send subscription confirmation
                response.writer.write("event: subscribed\n")
                response.writer.write("data: {\"type\":\"subscribed\",\"sessionId\":\"${sessionId}\",\"timestamp\":\"${System.currentTimeMillis()}\",\"architecture\":\"Service-based with Visit persistence\"}\n\n")
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
                    architecture: "Service-based with Visit persistence"
                ],
                connections: [
                    active: activeSessions.size(),
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
            
            // Require authentication - do not fallback to admin
            if (!ec.user?.userId) {
                logger.warn("Legacy MCP Request denied - no authenticated user")
                // Handle error directly without sendError to avoid Moqui error screen interference
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
                response.setContentType("application/json")
                response.writer.write(groovy.json.JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32000, message: "Authentication required. Please provide valid credentials."],
                    id: null
                ]))
                return
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
        
        VisitBasedMcpSession session = activeSessions.get(sessionId)
        if (!sessionId || !session || !session.isActive()) {
            throw new IllegalArgumentException("Invalid or expired session")
        }
        
        // Store subscription (in a real implementation, you'd maintain subscription lists)
        // For now, just confirm subscription
        
        // Send subscription confirmation via SSE
        def subscriptionData = [
            type: "subscription_confirmed",
            sessionId: sessionId,
            eventType: eventType,
            timestamp: System.currentTimeMillis(),
            architecture: "Service-based with Visit persistence"
        ]
        session.sendMessage(new JsonRpcNotification("subscribed", subscriptionData))
        
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
    
    private void broadcastSseEvent(String eventType, Map data) {
        activeSessions.keySet().each { sessionId ->
            VisitBasedMcpSession session = activeSessions.get(sessionId)
            if (session && session.isActive()) {
                try {
                    session.sendMessage(new JsonRpcNotification(eventType, data))
                } catch (Exception e) {
                    logger.warn("Failed to send broadcast event to ${sessionId}: ${e.message}")
                    activeSessions.remove(sessionId)
                }
            }
        }
    }
    
    private void sendSseEvent(PrintWriter writer, String eventType, String data, long eventId = -1) throws IOException {
        try {
            if (eventId >= 0) {
                writer.write("id: " + eventId + "\n")
            }
            writer.write("event: " + eventType + "\n")
            writer.write("data: " + data + "\n\n")
            writer.flush()
            
            if (writer.checkError()) {
                throw new IOException("Client disconnected")
            }
        } catch (Exception e) {
            throw new IOException("Failed to send SSE event: " + e.message, e)
        }
    }
    
    private void startKeepAliveTask() {
        executorService.scheduleWithFixedDelay({
            try {
                activeSessions.keySet().each { sessionId ->
                    VisitBasedMcpSession session = activeSessions.get(sessionId)
                    if (session && session.isActive()) {
                        def pingData = [
                            type: "ping",
                            timestamp: System.currentTimeMillis(),
                            connections: activeSessions.size(),
                            architecture: "Service-based with Visit persistence"
                        ]
                        session.sendMessage(new JsonRpcNotification("ping", pingData))
                    } else {
                        // Remove inactive session
                        activeSessions.remove(sessionId)
                    }
                }
            } catch (Exception e) {
                logger.warn("Error in Service-Based keep-alive task: ${e.message}")
            }
        }, keepAliveIntervalSeconds, keepAliveIntervalSeconds, TimeUnit.SECONDS)
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