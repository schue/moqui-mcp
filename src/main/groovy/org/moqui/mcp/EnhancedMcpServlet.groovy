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
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactTarpitException
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.entity.EntityValue
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.sql.Timestamp
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
    
        // Session state constants
        private static final int STATE_UNINITIALIZED = 0
        private static final int STATE_INITIALIZING = 1
        private static final int STATE_INITIALIZED = 2
        
        // Simple registry for active connections only (transient HTTP connections)
        private final Map<String, PrintWriter> activeConnections = new ConcurrentHashMap<>()
        
        // Session management using Moqui's Visit system directly
        // No need for separate session manager - Visit entity handles persistence
        private final Map<String, Integer> sessionStates = new ConcurrentHashMap<>()
        
        // Message storage for notifications/subscribe and notifications/unsubscribe
        private final Map<String, List<Map>> sessionMessages = new ConcurrentHashMap<>()
        
        // In-memory session tracking to avoid database access for read operations
        private final Map<String, String> sessionUsers = new ConcurrentHashMap<>()
    
    // Progress tracking for notifications/progress
    private final Map<String, Map> sessionProgress = new ConcurrentHashMap<>()
    
    // Visit cache to reduce database access and prevent lock contention
    private final Map<String, EntityValue> visitCache = new ConcurrentHashMap<>()
    
    // Notification queue for server-initiated notifications (for non-SSE clients)
    private static final Map<String, List<Map>> notificationQueues = new ConcurrentHashMap<>()
    
    // Throttled session activity tracking to prevent database lock contention
    private final Map<String, Long> lastActivityUpdate = new ConcurrentHashMap<>()
    private static final long ACTIVITY_UPDATE_INTERVAL_MS = 30000 // 30 seconds
    
    // Session-specific locks to avoid sessionId.intern() deadlocks
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>()
    
    // Configuration parameters
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
        
        String webappName = config.getInitParameter("moqui-name") ?: 
            config.getServletContext().getInitParameter("moqui-name")
        
        // Register servlet instance in context for service access
        config.getServletContext().setAttribute("enhancedMcpServlet", this)
        
        logger.info("EnhancedMcpServlet initialized for webapp ${webappName}")
        logger.info("SSE endpoint: ${sseEndpoint}, Message endpoint: ${messageEndpoint}")
        logger.info("Keep-alive interval: ${keepAliveIntervalSeconds}s, Max connections: ${maxConnections}")
        logger.info("Servlet instance registered in context as 'enhancedMcpServlet'")
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
            // Read request body VERY early before any other processing can consume it
            String requestBody = null
            if ("POST".equals(request.getMethod())) {
                try {
                    logger.debug("Early reading request body, content length: ${request.getContentLength()}")
                    BufferedReader reader = request.getReader()
                    StringBuilder body = new StringBuilder()
                    String line
                    int lineCount = 0
                    while ((line = reader.readLine()) != null) {
                        body.append(line)
                        lineCount++
                    }
                    requestBody = body.toString()
                    logger.debug("Early read ${lineCount} lines, request body length: ${requestBody.length()}")
                } catch (Exception e) {
                    logger.error("Failed to read request body early: ${e.message}")
                }
            }

            // Initialize web facade early to set up session and visit context
            try {
                ec.initWebFacade(webappName, request, response)
            } catch (Exception e) {
                logger.warn("Web facade initialization warning: ${e.message}")
            }

            // Authentication is handled by MoquiAuthFilter - user context should already be set
            if (!ec.user?.userId) {
                logger.warn("Enhanced MCP - no authenticated user after MoquiAuthFilter")
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
                response.setContentType("application/json")
                response.writer.write(JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32003, message: "Authentication required. Use Basic auth with valid Moqui credentials."],
                    id: null
                ]))
                return
            }
            
            // Get Visit created by web facade
            def visit = ec.user.getVisit()
            if (!visit) {
                logger.error("Web facade initialized but no Visit created")
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to create Visit")
                return
            }
            
            // Route based on request method and path
            String requestURI = request.getRequestURI()
            String method = request.getMethod()
            logger.debug("Enhanced MCP Request: ${method} ${requestURI} - Content-Length: ${request.getContentLength()}")

            if ("GET".equals(method) && requestURI.endsWith("/sse")) {
                handleSseConnection(request, response, ec, webappName)
            } else if ("POST".equals(method) && requestURI.endsWith("/message")) {
                handleMessage(request, response, ec, requestBody)
            } else if ("POST".equals(method) && (requestURI.equals("/mcp") || requestURI.endsWith("/mcp"))) {
                // Handle POST requests to /mcp for JSON-RPC
                logger.debug("About to call handleJsonRpc with visit: ${visit?.visitId}")
                handleJsonRpc(request, response, ec, webappName, requestBody, visit)
            } else if ("GET".equals(method) && (requestURI.equals("/mcp") || requestURI.endsWith("/mcp"))) {
                // Handle GET requests to /mcp - maybe for server info or SSE fallback
                handleSseConnection(request, response, ec, webappName)
            } else {
                // Fallback to JSON-RPC handling
                handleJsonRpc(request, response, ec, webappName, requestBody, visit)
            }
            
        } catch (ArtifactAuthorizationException e) {
            logger.warn("Enhanced MCP Access Forbidden (no authz): " + e.message)
            response.setStatus(HttpServletResponse.SC_FORBIDDEN)
            response.setContentType("application/json")
            def msg = e.message?.toString() ?: "Access forbidden"
            response.writer.write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"Access Forbidden: ${msg.replace("\"", "\\\"")}\"},\"id\":null}")
        } catch (ArtifactTarpitException e) {
            logger.warn("Enhanced MCP Too Many Requests (tarpit): " + e.message)
            response.setStatus(429)
            if (e.getRetryAfterSeconds()) {
                response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            }
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32002, message: "Too Many Requests: " + e.message],
                id: null
            ]))
        } catch (Throwable t) {
            logger.error("Error in Enhanced MCP request", t)
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            response.setContentType("application/json")
            // Use simple JSON string to avoid Groovy JSON library issues
            def errorMsg = t.message?.toString() ?: "Unknown error"
            response.writer.write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error: ${errorMsg.replace("\"", "\\\"")}\"},\"id\":null}")
        } finally {
            ec.destroy()
        }
    }
    
    private void handleSseConnection(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec, String webappName)
            throws IOException {

        logger.debug("Handling Enhanced SSE connection from ${request.remoteAddr}")
        
        // Check for existing session ID first
        String sessionId = request.getHeader("Mcp-Session-Id")
        def visit = null
        
        // If we have a session ID, validate using in-memory tracking
        if (sessionId) {
            try {
                String sessionUser = sessionUsers.get(sessionId)
                
                if (sessionUser) {
                    // Verify user has access to this session using in-memory data
                    if (!ec.user.userId || sessionUser != ec.user.userId.toString()) {
                        logger.warn("Session userId ${sessionUser} doesn't match current user userId ${ec.user.userId} - access denied")
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied for session: " + sessionId)
                        return
                    }
                    // Get Visit from cache for activity updates (but not for validation)
                    visit = getCachedVisit(ec, sessionId)
                } else {
                    logger.warn("Session not found in memory: ${sessionId}")
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Session not found: " + sessionId)
                    return
                }
            } catch (Exception e) {
                logger.error("Error validating session: ${e.message}", e)
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Session validation error")
                return
            }
        }
        
        // Only create new Visit if we didn't find an existing one
        if (!visit) {
            // Initialize web facade for Visit creation, but avoid screen resolution
            // Modify request path to avoid ScreenResourceNotFoundException
            String originalRequestURI = request.getRequestURI()
            String originalPathInfo = request.getPathInfo()
            request.setAttribute("jakarta.servlet.include.request_uri", "/mcp")
            request.setAttribute("jakarta.servlet.include.path_info", "")
            
        try {
            ec.initWebFacade(webappName, request, response)
            // Web facade should always create a Visit - if it doesn't, that's a system error
            visit = ec.user.getVisit()
            if (!visit) {
                logger.error("Web facade succeeded but no Visit created - this is a system configuration error")
                throw new Exception("Web facade succeeded but no Visit created - check Moqui configuration")
            }
            logger.debug("Web facade created Visit ${visit.visitId} for user ${ec.user.username}")
            // Store user mapping in memory for fast validation
            sessionUsers.put(visit.visitId.toString(), ec.user.userId.toString())
            logger.info("Created new Visit ${visit.visitId} for user ${ec.user.username}")
        } catch (Exception e) {
            logger.error("Web facade initialization failed - this is a system configuration error: ${e.message}", e)
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "System configuration error: Web facade failed to initialize. Check Moqui logs for details.")
            return
        }
        }
        
        // Final check that we have a Visit
        if (!visit) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to create Visit")
            return
        }
        
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
        
        // Register active connection (transient HTTP connection)
        activeConnections.put(visit.visitId, response.writer)
        
        // Create Visit-based session transport (for persistence)
        VisitBasedMcpSession session = new VisitBasedMcpSession(visit, response.writer, ec)
        
        try {
            // Check if this is old HTTP+SSE transport (no session ID, no prior initialization)
            // Send endpoint event first for backwards compatibility
            if (!request.getHeader("Mcp-Session-Id")) {
                logger.debug("No Mcp-Session-Id header detected, assuming old HTTP+SSE transport")
                sendSseEvent(response.writer, "endpoint", "/mcp", 0)
            }

            // Send initial connection event for new transport
                def connectData = [
                    version: "2.0.2",
                    protocolVersion: "2025-06-18",
                    architecture: "Visit-based sessions with connection registry"
                ]
            
            // Set MCP session ID header per specification BEFORE sending any data
            response.setHeader("Mcp-Session-Id", visit.visitId.toString())
            logger.debug("Set Mcp-Session-Id header to ${visit.visitId} for SSE connection")
            
            sendSseEvent(response.writer, "connect", JsonOutput.toJson(connectData), 1)
            
            // Keep connection alive with periodic pings
            int pingCount = 0
            while (!response.isCommitted() && pingCount < 60) { // 5 minutes max
                Thread.sleep(5000) // Wait 5 seconds
                
                if (!response.isCommitted()) {
                    def pingData = [
                        type: "ping",
                        timestamp: System.currentTimeMillis(),
                        sessionId: visit.visitId,
                        architecture: "Visit-based sessions"
                    ]
                    sendSseEvent(response.writer, "ping", JsonOutput.toJson(pingData), pingCount + 2)
                    pingCount++
                    
                    // Update session activity throttled (every 6th ping = every 30 seconds)
                    if (pingCount % 6 == 0) {
                        updateSessionActivityThrottled(visit.visitId.toString())
                    }
                }
            }
            
        } catch (InterruptedException e) {
            logger.info("SSE connection interrupted for session ${visit.visitId}")
            Thread.currentThread().interrupt()
        } catch (Exception e) {
            logger.warn("Enhanced SSE connection error: ${e.message}", e)
            } finally {
                // Clean up session - Visit persistence handles cleanup automatically
                try {
                    def closeData = [
                        type: "disconnected", 
                        sessionId: visit.visitId,
                        timestamp: System.currentTimeMillis()
                    ]
                    sendSseEvent(response.writer, "disconnect", JsonOutput.toJson(closeData), -1)
                } catch (Exception e) {
                    // Ignore errors during cleanup
                }
                
                // Remove from active connections registry
                activeConnections.remove(visit.visitId)
                
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
    
    private void handleMessage(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec, String requestBody) 
            throws IOException {
        
        String sessionId = request.getHeader("Mcp-Session-Id")
        def visit = getCachedVisit(ec, sessionId)
        if (!visit) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Session not found: " + sessionId)
            return
        }
        
        // Verify user has access to this Visit - rely on Moqui security
        logger.debug("Session validation: visit.userId=${visit.userId}, ec.user.userId=${ec.user.userId}, ec.user.username=${ec.user.username}")
        if (visit.userId && ec.user.userId && visit.userId.toString() != ec.user.userId.toString()) {
            logger.warn("Visit userId ${visit.userId} doesn't match current user userId ${ec.user.userId} - access denied")
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.setStatus(HttpServletResponse.SC_FORBIDDEN)
            response.writer.write(JsonOutput.toJson([
                error: "Access denied for session: " + sessionId + " (visit.userId=${visit.userId}, ec.user.userId=${ec.user.userId})",
                architecture: "Visit-based sessions"
            ]))
            return
        }
        
        // Create session wrapper for this Visit
        VisitBasedMcpSession session = new VisitBasedMcpSession(visit, response.writer, ec)
        
        try {
            if (!requestBody || !requestBody.trim()) {
                response.setContentType("application/json")
                response.setCharacterEncoding("UTF-8")
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
                response.writer.write(JsonOutput.toJson([
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
                response.writer.write(JsonOutput.toJson([
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
                response.writer.write(JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32600, message: "Invalid JSON-RPC 2.0 request"],
                    id: rpcRequest?.id ?: null
                ]))
                return
            }
            
            // Process method with session context
            def result = processMcpMethod(rpcRequest.method, rpcRequest.params, ec, sessionId)
            
            // Send response via MCP transport to the specific session
            def responseMessage = new JsonRpcResponse(result, rpcRequest.id)
            session.sendMessage(responseMessage)
            
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.setStatus(HttpServletResponse.SC_OK)
            
            // Extract actual result from service response (same as regular handler)
            def actualResult = result?.result ?: result
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                id: rpcRequest.id,
                result: actualResult
            ]))
            
        } catch (Exception e) {
            logger.error("Error processing message for session ${sessionId}: ${e.message}", e)
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32603, message: "Internal error: " + e.message],
                id: null
            ]))
        }
    }
    
    private void handleJsonRpc(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec, String webappName, String requestBody, def visit) 
            throws IOException {
        
        // Initialize web facade for proper session management
        try {
            // If we have a visit, use it directly (don't create new one)
            visit = ec.user.getVisit()
            if (visit) {
                request.getSession().setAttribute("moqui.visitId", visit.visitId)
                logger.debug("JSON-RPC web facade initialized for user: ${ec.user?.username} with visit: ${visit.visitId}")
            } else {
                // No visit exists, need to create one
                logger.info("Creating new Visit record for user: ${ec.user?.username}")
                visit = ec.entity.makeValue("moqui.server.Visit")
                visit.visitId = ec.userFacade.getVisitId(visit)
                visit.userId = ec.user.userId
                visit.sessionId = visit.sessionId
                visit.userAccountId = ec.user.userAccount?.userAccountId
                visit.sessionCreatedDate = ec.user.nowTimestamp
                visit.visitStatus = null
                visit.lastActiveDate = ec.user.nowTimestamp
                visit.visitDeletedDate = null
                ec.entity.create(visit)
                logger.info("Visit ${visit.visitId} created for user: ${ec.user?.username}")
            }
            ec.initWebFacade(webappName, request, response)
            logger.debug("JSON-RPC web facade initialized for user: ${ec.user?.username} with visit: ${visit.visitId}")
        } catch (Exception e) {
            logger.warn("Web facade initialization warning: ${e.message}")
            // Continue anyway - we may still have basic user context from auth
        }
        
        String method = request.getMethod()
        String acceptHeader = request.getHeader("Accept")

        logger.debug("Enhanced MCP JSON-RPC Request: ${method} ${request.requestURI} - Accept: ${acceptHeader}")

        // Validate Accept header per MCP 2025-11-25 spec requirement #2
        // Client MUST include Accept header with at least one of: application/json or text/event-stream
        if (!acceptHeader || !(acceptHeader.contains("application/json") || acceptHeader.contains("text/event-stream"))) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32600, message: "Accept header must include application/json or text/event-stream"],
                id: null
            ]))
            return
        }
        
        if (!"POST".equals(method)) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32601, message: "Method Not Allowed. Use POST for JSON-RPC."],
                id: null
            ]))
            return
        }

        // Use pre-read request body
        logger.debug("Using pre-read request body, length: ${requestBody?.length()}")

        if (!requestBody) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32602, message: "Empty request body"],
                id: null
            ]))
            return
        }

        // Log request body for debugging (be careful with this in production)
        if (requestBody.length() > 0) {
            logger.trace("MCP JSON-RPC request body: ${requestBody}")
        }

        def rpcRequest
        try {
            rpcRequest = jsonSlurper.parseText(requestBody)
        } catch (Exception e) {
            logger.error("Failed to parse JSON-RPC request: ${e.message}")
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
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
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32600, message: "Invalid JSON-RPC 2.0 request"],
                id: null
            ]))
            return
        }
        
        // Validate MCP protocol version per specification
        String protocolVersion = request.getHeader("MCP-Protocol-Version")
        // Support multiple protocol versions with version negotiation
        def supportedVersions = ["2025-06-18", "2025-11-25", "2024-11-05", "2024-10-07", "2023-06-05"]
        if (protocolVersion && !supportedVersions.contains(protocolVersion)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32600, message: "Unsupported MCP protocol version: ${protocolVersion}. Supported: ${supportedVersions.join(', ')}"],
                id: null
            ]))
            return
        }

        // Get session ID from Mcp-Session-Id header per MCP specification
        String sessionId = request.getHeader("Mcp-Session-Id")
        logger.debug("Session ID from header: '${sessionId}', method: '${rpcRequest.method}'")

        // For initialize and notifications/initialized methods, use visit ID as session ID if no header
        if (!sessionId && ("initialize".equals(rpcRequest.method) || "notifications/initialized".equals(rpcRequest.method)) && visit) {
            sessionId = visit.visitId
            logger.debug("${rpcRequest.method} method: using visit ID as session ID: ${sessionId}")
        }
        
        // Validate session ID for non-initialize requests per MCP spec
        // Allow notifications/initialized without session ID as it completes the initialization process
        if (!sessionId && rpcRequest.method != "initialize" && rpcRequest.method != "notifications/initialized") {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32600, message: "Mcp-Session-Id header required for non-initialize requests"],
                id: rpcRequest.id
            ]))
            return
        }
        
        // For existing sessions, set visit ID in HTTP session before web facade initialization
        // This ensures Moqui picks up the existing Visit when initWebFacade() is called
        if (sessionId && rpcRequest.method != "initialize") {
            try {
                ec.artifactExecution.disableAuthz()
                def existingVisit = ec.entity.find("moqui.server.Visit")
                    .condition("visitId", sessionId)
                    .one()
                
                if (!existingVisit) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND)
                    response.setContentType("application/json")
                    response.writer.write(JsonOutput.toJson([
                        jsonrpc: "2.0",
                        error: [code: -32600, message: "Session not found: ${sessionId}"],
                        id: rpcRequest.id
                    ]))
                    return
                }
                
                // Rely on Moqui security - only allow access if visit and current user match
                if (!existingVisit.userId || !ec.user.userId || existingVisit.userId.toString() != ec.user.userId.toString()) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN)
                    response.setContentType("application/json")
                    response.writer.write(JsonOutput.toJson([
                        jsonrpc: "2.0",
                        error: [code: -32600, message: "Access denied for session: ${sessionId}"],
                        id: rpcRequest.id
                    ]))
                    return
                }

                // Set visit ID in HTTP session so Moqui web facade initialization picks it up
                request.session.setAttribute("moqui.visitId", sessionId)
                logger.debug("Set existing Visit ${sessionId} in HTTP session for user ${ec.user.username}")

            } catch (Exception e) {
                logger.error("Error finding session ${sessionId}: ${e.message}")
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                response.setContentType("application/json")
                response.writer.write(JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32603, message: "Session lookup error: ${e.message}"],
                    id: rpcRequest.id
                ]))
                return
            } finally {
                ec.artifactExecution.enableAuthz()
            }
        }
        
        // Check if this is a notification (no id) - notifications get empty response
        boolean isNotification = !rpcRequest.containsKey('id')
        
        if (isNotification) {
            // Special handling for notifications/initialized to transition session state
            if ("notifications/initialized".equals(rpcRequest.method)) {
                logger.debug("Processing notifications/initialized for sessionId: ${sessionId}")
                if (sessionId) {
                    sessionStates.put(sessionId, STATE_INITIALIZED)
                    // Store user mapping in memory for fast validation
                    sessionUsers.put(sessionId, ec.user.userId.toString())
                    logger.debug("Session ${sessionId} transitioned to INITIALIZED state for user ${ec.user.userId}")
                }
                
                // For notifications/initialized, return 202 Accepted per MCP HTTP Streaming spec
                if (sessionId) {
                response.setHeader("Mcp-Session-Id", sessionId.toString())
            }
            response.setContentType("text/event-stream")
            response.setStatus(HttpServletResponse.SC_ACCEPTED)  // 202 Accepted
            logger.debug("Sent 202 Accepted response for notifications/initialized")
            response.flushBuffer()  // Commit the response immediately
            return
            }
            
            // For other notifications, set session header if needed but NO response per MCP spec
            if (sessionId) {
                response.setHeader("Mcp-Session-Id", sessionId.toString())
            }
            
            // Other notifications receive NO response per MCP specification
            response.setStatus(HttpServletResponse.SC_NO_CONTENT)  // 204 No Content
            response.flushBuffer()  // Commit the response immediately
            return
        }
        
        // Process MCP method using Moqui services with session ID if available
        def result = processMcpMethod(rpcRequest.method, rpcRequest.params, ec, sessionId, visit ?: [:])
        
        // Update session activity throttled for actual user actions (not pings or tools/list)
        // tools/list is read-only discovery and shouldn't update session activity to prevent lock contention
        if (sessionId && !"ping".equals(rpcRequest.method) && !"tools/list".equals(rpcRequest.method)) {
            updateSessionActivityThrottled(sessionId)
        }
        
        // Set Mcp-Session-Id header BEFORE any response data (per MCP 2025-06-18 spec)
        // For initialize method, always use sessionId we have (from visit or header)
        String responseSessionId = null
        if (rpcRequest.method == "initialize" && sessionId) {
            responseSessionId = sessionId.toString()
        } else if (result?.sessionId) {
            responseSessionId = result.sessionId.toString()
        } else if (sessionId) {
            // For other methods, ensure we always return session ID from header
            responseSessionId = sessionId.toString()
        }

        if (responseSessionId) {
            response.setHeader("Mcp-Session-Id", responseSessionId)
            logger.debug("Set Mcp-Session-Id header to ${responseSessionId} for method ${rpcRequest.method}")
        }

        // Build JSON-RPC response for regular requests
        // Extract the actual result from Moqui service response
        def actualResult = result?.result ?: result
        def rpcResponse = [
            jsonrpc: "2.0",
            id: rpcRequest.id,
            result: actualResult
        ]
        
        // Standard MCP flow: include notifications in response content array
        if (sessionId && notificationQueues.containsKey(sessionId)) {
            def pendingNotifications = notificationQueues.get(sessionId)
            if (pendingNotifications && !pendingNotifications.isEmpty()) {
                logger.debug("Adding ${pendingNotifications.size()} pending notifications to response content for session ${sessionId}")

                // Convert notifications to content items and add to result
                def notificationContent = []
                for (notification in pendingNotifications) {
                    notificationContent << [
                        type: "text",
                        text: "Notification [${notification.method}]: " + JsonOutput.toJson(notification.params ?: notification)
                    ]
                }
                
                // Merge notification content with existing result content
                def existingContent = actualResult?.content ?: []
                actualResult.content = existingContent + notificationContent
                
                // Clear delivered notifications
                notificationQueues.put(sessionId, [])
                logger.debug("Merged ${pendingNotifications.size()} notifications into response for session ${sessionId}")
            }
        }
        
        response.setContentType("application/json")
        response.setCharacterEncoding("UTF-8")
        
        // Send the main response
        response.writer.write(JsonOutput.toJson(rpcResponse))
    }

    private Map<String, Object> processMcpMethod(String method, Map params, ExecutionContextImpl ec, String sessionId, def visit) {
        logger.debug("Enhanced METHOD: ${method} with sessionId: ${sessionId}")

        try {
            // Ensure params is not null
            if (params == null) {
                params = [:]
            }
            
            // Add session context to parameters for services
            params.sessionId = visit?.visitId
            
            // Check session state for methods that require initialization
            // Use the sessionId from header for consistency (this is what the client tracks)
            Integer sessionState = sessionId ? sessionStates.get(sessionId) : null
            
            // Methods that don't require initialized session
            if (!["initialize", "ping"].contains(method)) {
                if (sessionState != STATE_INITIALIZED) {
                    logger.warn("Method ${method} called but session ${sessionId} not initialized (state: ${sessionState})")
                    return [error: "Session not initialized. Call initialize first, then send notifications/initialized."]
                }
            }
            
            switch (method) {
                case "initialize":
                    // For initialize, use the visitId we just created instead of null sessionId from request
                    if (visit && visit.visitId) {
                        params.sessionId = visit.visitId
                        // Set session to initializing state using actual sessionId as key (for consistency)
                        sessionStates.put(params.sessionId, STATE_INITIALIZING)
                        logger.debug("Initialize - using visitId: ${visit.visitId}, set state ${params.sessionId} to INITIALIZING")
                    } else {
                        logger.warn("Initialize - no visit available, using null sessionId")
                    }
                    params.actualUserId = ec.user.userId
                    logger.debug("Initialize - actualUserId: ${params.actualUserId}, sessionId: ${params.sessionId}")
                    def serviceResult = callMcpService("mcp#Initialize", params, ec)
                    // Add sessionId to the response for mcp.sh compatibility
                    if (serviceResult && serviceResult.result) {
                        serviceResult.result.sessionId = params.sessionId
                        // Initialize successful - transition session to INITIALIZED state
                        sessionStates.put(params.sessionId, STATE_INITIALIZED)
                        logger.debug("Initialize - successful, set state ${params.sessionId} to INITIALIZED")
                    }
                    return serviceResult
                case "ping":
                    // Simple ping for testing - bypass service for now
                    return [pong: System.currentTimeMillis(), sessionId: visit?.visitId, user: ec.user.username]
                case "tools/list":
                    // Ensure sessionId is available to service for notification consistency
                    if (sessionId) params.sessionId = sessionId
                    return callMcpService("list#Tools", params, ec)
                case "tools/call":
                    // Ensure sessionId is available to service for notification consistency
                    if (sessionId) params.sessionId = sessionId
                    return callMcpService("mcp#ToolsCall", params, ec)
                case "resources/list":
                    return callMcpService("mcp#ResourcesList", params, ec)
                case "resources/read":
                    return callMcpService("mcp#ResourcesRead", params, ec)
                case "resources/templates/list":
                    return callMcpService("mcp#ResourcesTemplatesList", params, ec)
                case "resources/subscribe":
                    return callMcpService("mcp#ResourcesSubscribe", params, ec)
                case "resources/unsubscribe":
                    return callMcpService("mcp#ResourcesUnsubscribe", params, ec)
                case "prompts/list":
                    return callMcpService("mcp#PromptsList", params, ec)
                case "prompts/get":
                    return callMcpService("mcp#PromptsGet", params, ec)
                case "roots/list":
                    return callMcpService("mcp#RootsList", params, ec)
                case "sampling/createMessage":
                    return callMcpService("mcp#SamplingCreateMessage", params, ec)
                case "elicitation/create":
                    return callMcpService("mcp#ElicitationCreate", params, ec)
                // NOTE: notifications/initialized is handled as a notification, not a request method
                // It will be processed by the notification handling logic above (lines 824-837)
                case "notifications/tools/list_changed":
                    // Handle tools list changed notification
                    logger.debug("Tools list changed for sessionId: ${sessionId}")
                    // Could trigger cache invalidation here if needed
                    return null
                case "notifications/resources/list_changed":
                    // Handle resources list changed notification
                    logger.debug("Resources list changed for sessionId: ${sessionId}")
                    // Could trigger cache invalidation here if needed
                    return null
                case "notifications/send":
                    // Handle notification sending
                    def notificationMethod = params?.method
                    def notificationParams = params?.params
                    if (!notificationMethod) {
                        throw new IllegalArgumentException("method is required for sending notification")
                    }

                    logger.debug("Sending notification ${notificationMethod} for sessionId: ${sessionId}")

                    // Queue notification for delivery through SSE or polling
                    if (sessionId) {
                        def notification = [
                            method: notificationMethod,
                            params: notificationParams,
                            timestamp: System.currentTimeMillis()
                        ]
                        
                        // Add to notification queue
                        def queue = notificationQueues.get(sessionId) ?: []
                        queue << notification
                        notificationQueues.put(sessionId, queue)

                        logger.debug("Notification queued for session ${sessionId}: ${notificationMethod}")
                    }

                    return [sent: true, sessionId: sessionId, method: notificationMethod]
                case "notifications/subscribe":
                    // Handle notification subscription
                    def subscriptionMethod = params?.method
                    if (!sessionId || !subscriptionMethod) {
                        throw new IllegalArgumentException("sessionId and method are required for subscription")
                    }
                    def subscriptions = sessionSubscriptions.get(sessionId) ?: new HashSet<>()
                    subscriptions.add(subscriptionMethod)
                    sessionSubscriptions.put(sessionId, subscriptions)
                    logger.debug("Session ${sessionId} subscribed to: ${subscriptionMethod}")
                    return [subscribed: true, sessionId: sessionId, method: subscriptionMethod]
                case "notifications/unsubscribe":
                    // Handle notification unsubscription
                    def subscriptionMethod = params?.method
                    if (!sessionId || !subscriptionMethod) {
                        throw new IllegalArgumentException("sessionId and method are required for unsubscription")
                    }
                    def subscriptions = sessionSubscriptions.get(sessionId)
                    if (subscriptions) {
                        subscriptions.remove(subscriptionMethod)
                        if (subscriptions.isEmpty()) {
                            sessionSubscriptions.remove(sessionId)
                        } else {
                            sessionSubscriptions.put(sessionId, subscriptions)
                        }
                        logger.debug("Session ${sessionId} unsubscribed from: ${subscriptionMethod}")
                    }
                    return [unsubscribed: true, sessionId: sessionId, method: subscriptionMethod]
                case "notifications/progress":
                    // Handle progress notification
                    def progressToken = params?.progressToken
                    def progressValue = params?.progress
                    def total = params?.total
                    logger.debug("Progress notification for sessionId: ${sessionId}, token: ${progressToken}, progress: ${progressValue}/${total}")
                    // Store progress for potential polling
                    if (sessionId && progressToken) {
                        def progressKey = "${sessionId}_${progressToken}"
                        sessionProgress.put(progressKey, [progress: progressValue, total: total, timestamp: System.currentTimeMillis()])
                    }
                    return null
                case "notifications/resources/updated":
                    // Handle resource updated notification
                    def uri = params?.uri
                    logger.debug("Resource updated notification for sessionId: ${sessionId}, uri: ${uri}")
                    // Could trigger resource cache invalidation here
                    return null
                case "notifications/prompts/list_changed":
                    // Handle prompts list changed notification
                    logger.debug("Prompts list changed for sessionId: ${sessionId}")
                    // Could trigger prompt cache invalidation here
                    return null
                case "notifications/message":
                    // Handle general message notification
                    def level = params?.level ?: "info"
                    def message = params?.message
                    def data = params?.data
                    logger.debug("Message notification for sessionId: ${sessionId}, level: ${level}, message: ${message}")
                    // Store message for potential retrieval
                    if (sessionId) {
                        def messages = sessionMessages.get(sessionId) ?: []
                        messages << [level: level, message: message, data: data, timestamp: System.currentTimeMillis()]
                        sessionMessages.put(sessionId, messages)
                    }
                    return null
                case "notifications/roots/list_changed":
                    // Handle roots list changed notification
                    logger.debug("Roots list changed for sessionId: ${sessionId}")
                    // Could trigger roots cache invalidation here
                    return null
                case "logging/setLevel":
                    // Handle logging level change notification
                    logger.debug("Logging level change requested for sessionId: ${sessionId}")
                    return null
                default:
                    throw new IllegalArgumentException("Method not found: ${method}")
            }
        } catch (Exception e) {
            logger.error("Error processing MCP method ${method}: ${e.message}", e)
            throw e
        }
    }
    
    private Map<String, Object> callMcpService(String serviceName, Map params, ExecutionContextImpl ec) {
        logger.debug("Enhanced Calling MCP service: ${serviceName} with params: ${params}")
        
        try {
            ec.artifactExecution.disableAuthz()
            def result = ec.service.sync().name("McpServices.${serviceName}")
                .parameters(params ?: [:])
                .call()
            
            logger.debug("Enhanced MCP service ${serviceName} result: ${result?.result?.size() ? 'result with ' + (result.result?.tools?.size() ?: 0) + ' tools' : 'empty result'}")
            if (result == null) {
                logger.error("Enhanced MCP service ${serviceName} returned null result")
                return [error: "Service returned null result"]
            }
            // Service framework returns result in 'result' field when out-parameters are used
            // Extract the inner result to avoid double nesting in JSON-RPC response
            // The MCP services already set the correct 'result' structure
            // Some services return result directly, others nest it in result.result
            if (result?.containsKey('result')) {
                return result.result
            } else {
                return result ?: [error: "Service returned null result"]
            }
        } catch (Exception e) {
            logger.error("Error calling Enhanced MCP service ${serviceName}", e)
            return [error: e.message]
        } finally {
            ec.artifactExecution.enableAuthz()
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
    
    /**
     * Queue a server notification for delivery to client
     */
    void queueNotification(String sessionId, Map notification) {
        if (!sessionId || !notification) return

        def queue = notificationQueues.computeIfAbsent(sessionId) { [] }
        queue << notification
        logger.info("Queued notification for session ${sessionId}: ${notification}")

        // Session activity updates handled at JSON-RPC level, not notification level
        // This prevents excessive database updates during notification processing

        // Also try to send via SSE if active connection exists
        def writer = activeConnections.get(sessionId)
        if (writer && !writer.checkError()) {
            try {
                // Send as proper JSON-RPC notification via SSE
                def notificationMessage = [
                    jsonrpc: "2.0",
                    method: notification.method ?: "notifications/message",
                    params: notification.params ?: notification
                ]
                sendSseEvent(writer, "message", JsonOutput.toJson(notificationMessage), System.currentTimeMillis())
                logger.debug("Sent notification via SSE to session ${sessionId}")
            } catch (Exception e) {
                logger.warn("Failed to send notification via SSE to session ${sessionId}: ${e.message}")
            }
        }
    }
    
    /**
     * Get Visit from cache to reduce database access and prevent lock contention
     */
    private EntityValue getCachedVisit(ExecutionContext ec, String sessionId) {
        if (!sessionId) return null
        
        EntityValue cachedVisit = visitCache.get(sessionId)
        if (cachedVisit != null) {
            return cachedVisit
        }
        
        // Not in cache, load from database with authz disabled
        try {
            ec.artifactExecution.disableAuthz()
            EntityValue visit = ec.entity.find("moqui.server.Visit")
                .condition("visitId", sessionId)
                .one()
            if (visit != null) {
                visitCache.put(sessionId, visit)
            }
            return visit
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }
    
    /**
     * Throttled session activity update to prevent database lock contention
     * Uses synchronized per-session to prevent concurrent updates
     */
    private void updateSessionActivityThrottled(String sessionId) {
        if (!sessionId) return
        
        long now = System.currentTimeMillis()
        Long lastUpdate = lastActivityUpdate.get(sessionId)
        
        // Only update if 30 seconds have passed since last update
        if (lastUpdate == null || (now - lastUpdate) > ACTIVITY_UPDATE_INTERVAL_MS) {
            // Use session-specific lock to avoid sessionId.intern() deadlocks
            Object sessionLock = sessionLocks.computeIfAbsent(sessionId, { new Object() })
            synchronized (sessionLock) {
                // Double-check after acquiring lock
                lastUpdate = lastActivityUpdate.get(sessionId)
                if (lastUpdate == null || (now - lastUpdate) > ACTIVITY_UPDATE_INTERVAL_MS) {
                    try {
                        // Look up Visit and update activity
                        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) getServletContext().getAttribute("executionContextFactory")
                        if (ecfi) {
                            def ec = ecfi.getEci()
                            try {
                                def visit = getCachedVisit(ec, sessionId)
                                
                                if (visit) {
                                    visit.thruDate = ec.user.getNowTimestamp()
                                    //visit.update()
                                    // Update cache with new thruDate
                                    visitCache.put(sessionId, visit)
                                    lastActivityUpdate.put(sessionId, now)
                                    logger.debug("Updated activity for session ${sessionId} (throttled, synchronized)")
                                }
                            } finally {
                                ec.destroy()
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to update session activity for ${sessionId}: ${e.message}")
                    }
                }
            }
        }
    }
    
    @Override
    void destroy() {
        logger.info("Destroying EnhancedMcpServlet")
        
        // Close all active connections
        activeConnections.values().each { writer ->
            try {
                writer.write("event: shutdown\ndata: {\"type\":\"shutdown\",\"timestamp\":\"${System.currentTimeMillis()}\"}\n\n")
                writer.flush()
            } catch (Exception e) {
                logger.debug("Error sending shutdown to connection: ${e.message}")
            }
        }
        activeConnections.clear()
        
        super.destroy()
    }
    
    /**
     * Broadcast message to all active MCP sessions
     */
    void broadcastToAllSessions(JsonRpcMessage message) {
        try {
            ec.artifactExecution.disableAuthz()
            // Look up all MCP Visits (persistent)
            def mcpVisits = ec.entity.find("moqui.server.Visit")
                .condition("initialRequest", "like", "%mcpSession%")
                .list()
            
            logger.info("Broadcasting to ${mcpVisits.size()} MCP visits, ${activeConnections.size()} active connections")
            
            int successCount = 0
            int failureCount = 0
            
            // Send to active connections (transient)
            mcpVisits.each { visit ->
                PrintWriter writer = activeConnections.get(visit.visitId)
                if (writer && !writer.checkError()) {
                    try {
                        sendSseEvent(writer, "message", message.toJson())
                        successCount++
                    } catch (Exception e) {
                        logger.warn("Failed to send broadcast to ${visit.visitId}: ${e.message}")
                        // Remove broken connection
                        activeConnections.remove(visit.visitId)
                        failureCount++
                    }
                } else {
                    // No active connection for this visit
                    failureCount++
                }
            }
            
            logger.info("Broadcast completed: ${successCount} successful, ${failureCount} failed")
            
        } catch (Exception e) {
            logger.error("Error broadcasting to all sessions: ${e.message}", e)
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }
    
    /**
     * Send SSE event to specific session (helper method)
     */
    void sendToSession(String sessionId, JsonRpcMessage message) {
        try {
            PrintWriter writer = activeConnections.get(sessionId)
            if (writer && !writer.checkError()) {
                sendSseEvent(writer, "message", message.toJson())
                logger.debug("Sent message to session ${sessionId}")
            } else {
                logger.warn("No active connection for session ${sessionId}")
            }
            } catch (Exception e) {
                logger.error("Error sending message to session ${sessionId}: ${e.message}", e)
                activeConnections.remove(sessionId)
                visitCache.remove(sessionId)
                sessionUsers.remove(sessionId)
        }
    }
    
    /**
     * Get session statistics for monitoring
     */
    Map getSessionStatistics() {
        try {
            // Look up all MCP Visits (persistent)
            def mcpVisits = ec.entity.find("moqui.server.Visit")
                .condition("initialRequest", "like", "%mcpSession%")
                .disableAuthz()
                .list()
            
            return [
                totalMcpVisits: mcpVisits.size(),
                activeConnections: activeConnections.size(),
                maxConnections: maxConnections,
                architecture: "Visit-based sessions with connection registry",
                message: "Enhanced MCP with session tracking",
                endpoints: [
                    sse: sseEndpoint,
                    message: messageEndpoint
                ],
                keepAliveInterval: keepAliveIntervalSeconds
            ]
        } catch (Exception e) {
            logger.error("Error getting session statistics: ${e.message}", e)
            return [
                activeConnections: activeConnections.size(), 
                maxConnections: maxConnections,
                error: e.message
            ]
        }
    }
}
