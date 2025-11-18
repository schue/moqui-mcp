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
            // Initialize web facade for authentication
            ec.initWebFacade(webappName, request, response)
            
            logger.info("Enhanced MCP Request authenticated user: ${ec.user?.username}, userId: ${ec.user?.userId}")
            
            // If no user authenticated, try to authenticate as admin for MCP requests
            if (!ec.user?.userId) {
                logger.info("No user authenticated, attempting admin login for Enhanced MCP")
                try {
                    ec.user.loginUser("admin", "admin")
                    logger.info("Enhanced MCP Admin login successful, user: ${ec.user?.username}")
                } catch (Exception e) {
                    logger.warn("Enhanced MCP Admin login failed: ${e.message}")
                }
            }
            
            // Route based on request method and path
            String requestURI = request.getRequestURI()
            String method = request.getMethod()
            
            if ("GET".equals(method) && requestURI.endsWith("/sse")) {
                handleSseConnection(request, response, ec)
            } else if ("POST".equals(method) && requestURI.endsWith("/message")) {
                handleMessage(request, response, ec)
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
        
        // Set SSE headers
        response.setContentType("text/event-stream")
        response.setCharacterEncoding("UTF-8")
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("Access-Control-Allow-Origin", "*")
        
        String sessionId = UUID.randomUUID().toString()
        String visitId = ec.web?.visitId
        
        // Create Visit-based session transport
        VisitBasedMcpSession session = new VisitBasedMcpSession(sessionId, visitId, response.writer, ec)
        sessionManager.registerSession(session)
        
        try {
            // Send initial connection event with endpoint info
            sendSseEvent(response.writer, "endpoint", "/mcp-sse/message?sessionId=" + sessionId)
            
            // Send initial resources list
            def resourcesResult = processMcpMethod("resources/list", [:], ec)
            sendSseEvent(response.writer, "resources", groovy.json.JsonOutput.toJson(resourcesResult))
            
            // Send initial tools list
            def toolsResult = processMcpMethod("tools/list", [:], ec)
            sendSseEvent(response.writer, "tools", groovy.json.JsonOutput.toJson(toolsResult))
            
            // Keep connection alive with periodic pings
            int pingCount = 0
            while (!response.isCommitted() && !sessionManager.isShuttingDown() && pingCount < 60) { // 5 minutes max
                Thread.sleep(5000) // Wait 5 seconds
                
                if (!response.isCommitted() && !sessionManager.isShuttingDown()) {
                    def pingMessage = new McpSchema.JSONRPCMessage([
                        type: "ping", 
                        count: pingCount, 
                        timestamp: System.currentTimeMillis()
                    ], null)
                    session.sendMessage(pingMessage)
                    pingCount++
                }
            }
            
        } catch (Exception e) {
            logger.warn("Enhanced SSE connection interrupted: ${e.message}")
        } finally {
            // Clean up session
            sessionManager.unregisterSession(sessionId)
            try {
                def closeMessage = new McpSchema.JSONRPCMessage([
                    type: "disconnected", 
                    timestamp: System.currentTimeMillis()
                ], null)
                session.sendMessage(closeMessage)
            } catch (Exception e) {
                // Ignore errors during cleanup
            }
        }
    }
    
    private void handleMessage(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec) 
            throws IOException {
        
        if (sessionManager.isShuttingDown()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down")
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
            BufferedReader reader = request.getReader()
            StringBuilder body = new StringBuilder()
            String line
            while ((line = reader.readLine()) != null) {
                body.append(line)
            }
            
            // Parse JSON-RPC message
            def rpcRequest = jsonSlurper.parseText(body.toString())
            
            // Process the method
            def result = processMcpMethod(rpcRequest.method, rpcRequest.params, ec)
            
            // Send response via MCP transport to the specific session
            def responseMessage = new McpSchema.JSONRPCMessage(result, rpcRequest.id)
            session.sendMessage(responseMessage)
            
            response.setStatus(HttpServletResponse.SC_OK)
            
        } catch (Exception e) {
            logger.error("Error processing message: ${e.message}")
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            response.writer.write(groovy.json.JsonOutput.toJson([
                error: e.message
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
            default:
                throw new IllegalArgumentException("Unknown MCP method: ${method}")
        }
    }
    
    private Map<String, Object> callMcpService(String serviceName, Map params, ExecutionContextImpl ec) {
        logger.info("Enhanced Calling MCP service: ${serviceName} with params: ${params}")
        
        try {
            def result = ec.service.sync().name("org.moqui.mcp.McpServices.${serviceName}")
                .parameters(params ?: [:])
                .call()
            
            logger.info("Enhanced MCP service ${serviceName} result: ${result}")
            return result.result
        } catch (Exception e) {
            logger.error("Error calling Enhanced MCP service ${serviceName}", e)
            throw e
        }
    }
    
    private void sendSseEvent(PrintWriter writer, String eventType, String data) throws IOException {
        writer.write("event: " + eventType + "\n")
        writer.write("data: " + data + "\n\n")
        writer.flush()
        
        if (writer.checkError()) {
            throw new IOException("Client disconnected")
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
        }
        sessions.clear()
        
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