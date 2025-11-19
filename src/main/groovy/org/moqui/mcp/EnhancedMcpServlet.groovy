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

import javax.servlet.ServletConfig
import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/**
 * Enhanced MCP Servlet with proper SSE handling inspired by HttpServletSseServerTransportProvider
 * This implementation provides better SSE support and session management.
 */
class EnhancedMcpServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(EnhancedMcpServlet.class)
    
    private JsonSlurper jsonSlurper = new JsonSlurper()
    
    // Session management using dedicated session manager
    private final McpSessionManager sessionManager = new McpSessionManager()
    
    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)
        String webappName = config.getInitParameter("moqui-name") ?: 
            config.getServletContext().getInitParameter("moqui-name")
        logger.info("EnhancedMcpServlet initialized for webapp ${webappName}")
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
        
        long startTime = System.currentTimeMillis()
        
        if (logger.traceEnabled) {
            logger.trace("Start Enhanced MCP request to [${request.getPathInfo()}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")
        }
        
        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null) {
            logger.warn("In EnhancedMcpServlet.service there is already an ExecutionContext for user ${activeEc.user.username}")
            activeEc.destroy()
        }
        
        ExecutionContextImpl ec = ecfi.getEci()
        
try {
            // Handle Basic Authentication directly without triggering screen system
            String authzHeader = request.getHeader("Authorization")
            boolean authenticated = false
            
            if (authzHeader != null && authzHeader.length() > 6 && authzHeader.startsWith("Basic ")) {
                String basicAuthEncoded = authzHeader.substring(6).trim()
                String basicAuthAsString = new String(basicAuthEncoded.decodeBase64())
                int indexOfColon = basicAuthAsString.indexOf(":")
                if (indexOfColon > 0) {
                    String username = basicAuthAsString.substring(0, indexOfColon)
                    String password = basicAuthAsString.substring(indexOfColon + 1)
                    try {
                        ec.user.loginUser(username, password)
                        authenticated = true
                        logger.info("Enhanced MCP Basic auth successful for user: ${ec.user?.username}")
                    } catch (Exception e) {
                        logger.warn("Enhanced MCP Basic auth failed for user ${username}: ${e.message}")
                    }
                } else {
                    logger.warn("Enhanced MCP got bad Basic auth credentials string")
                }
            }
            
            // Check if user is authenticated
            if (!authenticated || !ec.user?.userId) {
                logger.warn("Enhanced MCP authentication failed - no valid user authenticated")
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
                response.setContentType("application/json")
                response.setHeader("WWW-Authenticate", "Basic realm=\"Moqui MCP\"")
                response.writer.write(groovy.json.JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32003, message: "Authentication required. Use Basic auth with valid Moqui credentials."],
                    id: null
                ]))
                return
            }
            
            // Route based on request method and path
            String requestURI = request.getRequestURI()
            String method = request.getMethod()
            
            if ("GET".equals(method) && requestURI.endsWith("/sse")) {
                handleSseConnection(request, response, ec)
            } else if ("POST".equals(method) && requestURI.endsWith("/message")) {
                handleMessage(request, response, ec)
            } else if ("POST".equals(method) && (requestURI.equals("/mcp") || requestURI.endsWith("/mcp"))) {
                // Handle POST requests to /mcp for JSON-RPC
                handleJsonRpc(request, response, ec)
            } else if ("GET".equals(method) && (requestURI.equals("/mcp") || requestURI.endsWith("/mcp"))) {
                // Handle GET requests to /mcp - maybe for server info or SSE fallback
                handleSseConnection(request, response, ec)
            } else {
                // Fallback to JSON-RPC handling
                handleJsonRpc(request, response, ec)
            }
            
        } catch (ArtifactAuthorizationException e) {
            logger.warn("Enhanced MCP Access Forbidden (no authz): " + e.message)
            response.setStatus(HttpServletResponse.SC_FORBIDDEN)
            response.setContentType("application/json")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32001, message: "Access Forbidden: " + e.message],
                id: null
            ]))
        } catch (ArtifactTarpitException e) {
            logger.warn("Enhanced MCP Too Many Requests (tarpit): " + e.message)
            response.setStatus(429)
            if (e.getRetryAfterSeconds()) {
                response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            }
            response.setContentType("application/json")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32002, message: "Too Many Requests: " + e.message],
                id: null
            ]))
        } catch (Throwable t) {
            logger.error("Error in Enhanced MCP request", t)
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            response.setContentType("application/json")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32603, message: "Internal error: " + t.message],
                id: null
            ]))
        } finally {
            ec.destroy()
        }
    }
    
    private void handleSseConnection(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec) 
            throws IOException {
        
        if (sessionManager.isShuttingDown()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down")
            return
        }
        
        logger.info("Handling Enhanced SSE connection from ${request.remoteAddr}")
        
        // Enable async support for SSE
        if (request.isAsyncSupported()) {
            request.startAsync()
        }
        
        // Set SSE headers
        response.setContentType("text/event-stream")
        response.setCharacterEncoding("UTF-8")
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("Access-Control-Allow-Origin", "*")
        response.setHeader("X-Accel-Buffering", "no") // Disable nginx buffering
        
        String sessionId = UUID.randomUUID().toString()
        String visitId = ec.user?.visitId
        
        // Create Visit-based session transport
        VisitBasedMcpSession session = new VisitBasedMcpSession(sessionId, visitId, response.writer, ec)
        sessionManager.registerSession(session)
        
        try {
            // Send initial connection event
            def connectData = [
                type: "connected",
                sessionId: sessionId,
                timestamp: System.currentTimeMillis(),
                serverInfo: [
                    name: "Moqui MCP SSE Server",
                    version: "2.0.0",
                    protocolVersion: "2025-06-18"
                ]
            ]
            sendSseEvent(response.writer, "connect", groovy.json.JsonOutput.toJson(connectData), 0)
            
            // Send endpoint info for message posting
            sendSseEvent(response.writer, "endpoint", "/mcp/message?sessionId=" + sessionId, 1)
            
            // Keep connection alive with periodic pings
            int pingCount = 0
            while (!response.isCommitted() && !sessionManager.isShuttingDown() && pingCount < 60) { // 5 minutes max
                Thread.sleep(5000) // Wait 5 seconds
                
                if (!response.isCommitted() && !sessionManager.isShuttingDown()) {
                    def pingData = [
                        type: "ping",
                        timestamp: System.currentTimeMillis(),
                        connections: sessionManager.getActiveSessionCount()
                    ]
                    sendSseEvent(response.writer, "ping", groovy.json.JsonOutput.toJson(pingData), pingCount + 2)
                    pingCount++
                }
            }
            
        } catch (InterruptedException e) {
            logger.info("SSE connection interrupted for session ${sessionId}")
            Thread.currentThread().interrupt()
        } catch (Exception e) {
            logger.warn("Enhanced SSE connection error: ${e.message}", e)
        } finally {
            // Clean up session
            sessionManager.unregisterSession(sessionId)
            try {
                def closeData = [
                    type: "disconnected", 
                    timestamp: System.currentTimeMillis()
                ]
                sendSseEvent(response.writer, "disconnect", groovy.json.JsonOutput.toJson(closeData), -1)
            } catch (Exception e) {
                // Ignore errors during cleanup
            }
            
            // Complete async context if available
            if (request.isAsyncStarted()) {
                try {
                    request.getAsyncContext().complete()
                } catch (Exception e) {
                    logger.debug("Error completing async context: ${e.message}")
                }
            }
        }
    }
    
    private void handleMessage(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec) 
            throws IOException {
        
        if (sessionManager.isShuttingDown()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down")
            return
        }
        
        // Get sessionId from request parameter or header
        String sessionId = request.getParameter("sessionId") ?: request.getHeader("Mcp-Session-Id")
        if (!sessionId) {
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.writer.write(groovy.json.JsonOutput.toJson([
                error: "Missing sessionId parameter or header",
                activeSessions: sessionManager.getActiveSessionCount()
            ]))
            return
        }
        
        // Get session from session manager
        VisitBasedMcpSession session = sessionManager.getSession(sessionId)
        if (session == null) {
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.setStatus(HttpServletResponse.SC_NOT_FOUND)
            response.writer.write(groovy.json.JsonOutput.toJson([
                error: "Session not found: " + sessionId,
                activeSessions: sessionManager.getActiveSessionCount()
            ]))
            return
        }
        
        try {
            // Read request body
            StringBuilder body = new StringBuilder()
            try {
                BufferedReader reader = request.getReader()
                String line
                while ((line = reader.readLine()) != null) {
                    body.append(line)
                }
            } catch (IOException e) {
                logger.error("Failed to read request body: ${e.message}")
                response.setContentType("application/json")
                response.setCharacterEncoding("UTF-8")
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
                response.writer.write(groovy.json.JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32700, message: "Failed to read request body: " + e.message],
                    id: null
                ]))
                return
            }
            
            String requestBody = body.toString()
            if (!requestBody.trim()) {
                response.setContentType("application/json")
                response.setCharacterEncoding("UTF-8")
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
                response.writer.write(groovy.json.JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32602, message: "Empty request body"],
                    id: null
                ]))
                return
            }
            
            // Parse JSON-RPC message
            def rpcRequest
            try {
                rpcRequest = jsonSlurper.parseText(requestBody)
            } catch (Exception e) {
                logger.error("Failed to parse JSON-RPC message: ${e.message}")
                response.setContentType("application/json")
                response.setCharacterEncoding("UTF-8")
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
                response.writer.write(groovy.json.JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32700, message: "Invalid JSON: " + e.message],
                    id: null
                ]))
                return
            }
            
            // Validate JSON-RPC 2.0 structure
            if (!rpcRequest?.jsonrpc || rpcRequest.jsonrpc != "2.0" || !rpcRequest?.method) {
                response.setContentType("application/json")
                response.setCharacterEncoding("UTF-8")
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
                response.writer.write(groovy.json.JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32600, message: "Invalid JSON-RPC 2.0 request"],
                    id: rpcRequest?.id ?: null
                ]))
                return
            }
            
            // Process the method
            def result = processMcpMethod(rpcRequest.method, rpcRequest.params, ec)
            
            // Send response via MCP transport to the specific session
            def responseMessage = new McpSchema.JSONRPCMessage(result, rpcRequest.id)
            session.sendMessage(responseMessage)
            
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.setStatus(HttpServletResponse.SC_OK)
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                id: rpcRequest.id,
                result: [status: "processed", sessionId: sessionId]
            ]))
            
        } catch (Exception e) {
            logger.error("Error processing message for session ${sessionId}: ${e.message}", e)
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32603, message: "Internal error: " + e.message],
                id: null
            ]))
        }
    }
    
    private void handleJsonRpc(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec) 
            throws IOException {
        
        String method = request.getMethod()
        String acceptHeader = request.getHeader("Accept")
        String contentType = request.getContentType()
        
        logger.info("Enhanced MCP JSON-RPC Request: ${method} ${request.requestURI} - Accept: ${acceptHeader}, Content-Type: ${contentType}")
        
        // Handle POST requests for JSON-RPC
        if (!"POST".equals(method)) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
            response.setContentType("application/json")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32601, message: "Method Not Allowed. Use POST for JSON-RPC or GET /mcp-sse/sse for SSE."],
                id: null
            ]))
            return
        }
        
        // Read and parse JSON-RPC request
        String requestBody
        try {
            BufferedReader reader = request.getReader()
            StringBuilder body = new StringBuilder()
            String line
            while ((line = reader.readLine()) != null) {
                body.append(line)
            }
            requestBody = body.toString()
            
        } catch (IOException e) {
            logger.error("Failed to read request body: ${e.message}")
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32700, message: "Failed to read request body: " + e.message],
                id: null
            ]))
            return
        }
        
        if (!requestBody) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32602, message: "Empty request body"],
                id: null
            ]))
            return
        }
        
        def rpcRequest
        try {
            rpcRequest = jsonSlurper.parseText(requestBody)
        } catch (Exception e) {
            logger.error("Failed to parse JSON-RPC request: ${e.message}")
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32700, message: "Invalid JSON: " + e.message],
                id: null
            ]))
            return
        }
        
        // Validate JSON-RPC 2.0 structure
        if (!rpcRequest?.jsonrpc || rpcRequest.jsonrpc != "2.0" || !rpcRequest?.method) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32600, message: "Invalid JSON-RPC 2.0 request"],
                id: null
            ]))
            return
        }
        
        // Process MCP method using Moqui services
        def result = processMcpMethod(rpcRequest.method, rpcRequest.params, ec)
        
        // Build JSON-RPC response
        def rpcResponse = [
            jsonrpc: "2.0",
            id: rpcRequest.id,
            result: result
        ]
        
        response.setContentType("application/json")
        response.setCharacterEncoding("UTF-8")
        response.writer.write(groovy.json.JsonOutput.toJson(rpcResponse))
    }
    
    private Map<String, Object> processMcpMethod(String method, Map params, ExecutionContextImpl ec) {
        logger.info("Enhanced METHOD: ${method} with params: ${params}")
        
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
                case "notifications/initialized":
                    // Handle notification initialization - return success for now
                    return [initialized: true]
                case "notifications/send":
                    // Handle notification sending - return success for now  
                    return [sent: true]
                case "notifications/subscribe":
                    // Handle notification subscription - return success for now
                    return [subscribed: true]
                case "notifications/unsubscribe":
                    // Handle notification unsubscription - return success for now
                    return [unsubscribed: true]
                default:
                    throw new IllegalArgumentException("Method not found: ${method}")
            }
        } catch (Exception e) {
            logger.error("Error processing MCP method ${method}: ${e.message}", e)
            throw e
        }
    }
    
    private Map<String, Object> callMcpService(String serviceName, Map params, ExecutionContextImpl ec) {
        logger.info("Enhanced Calling MCP service: ${serviceName} with params: ${params}")
        
        try {
            def result = ec.service.sync().name("McpServices.${serviceName}")
                .parameters(params ?: [:])
                .call()
            
            logger.info("Enhanced MCP service ${serviceName} result: ${result}")
            return result.result
        } catch (Exception e) {
            logger.error("Error calling Enhanced MCP service ${serviceName}", e)
            throw e
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
    
    @Override
    void destroy() {
        logger.info("Destroying EnhancedMcpServlet")
        
        // Gracefully shutdown session manager
        sessionManager.shutdownGracefully()
        
        super.destroy()
    }
    
    /**
     * Broadcast message to all active sessions
     */
    void broadcastToAllSessions(McpSchema.JSONRPCMessage message) {
        sessionManager.broadcast(message)
    }
    
    /**
     * Get session statistics for monitoring
     */
    Map getSessionStatistics() {
        return sessionManager.getSessionStatistics()
    }
}