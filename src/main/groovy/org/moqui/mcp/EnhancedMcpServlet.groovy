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
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.ServletConfig
import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
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
    
    // Progress tracking for notifications/progress
    private final Map<String, Map> sessionProgress = new ConcurrentHashMap<>()
    
    // Message storage for notifications/message
    private final Map<String, List<Map>> sessionMessages = new ConcurrentHashMap<>()
    
    // Subscription tracking for notifications/subscribe and notifications/unsubscribe
    private final Map<String, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>()
    
    // Notification queue for server-initiated notifications (for non-SSE clients)
    private static final Map<String, List<Map>> notificationQueues = new ConcurrentHashMap<>()
    
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
            // Handle Basic Authentication directly without triggering screen system
            String authzHeader = request.getHeader("Authorization")
            boolean authenticated = false
            
            // Read request body early before any other processing can consume it
            String requestBody = null
            if ("POST".equals(request.getMethod())) {
                try {
                    logger.info("Early reading request body, content length: ${request.getContentLength()}")
                    BufferedReader reader = request.getReader()
                    StringBuilder body = new StringBuilder()
                    String line
                    int lineCount = 0
                    while ((line = reader.readLine()) != null) {
                        body.append(line)
                        lineCount++
                    }
                    requestBody = body.toString()
                    logger.info("Early read ${lineCount} lines, request body length: ${requestBody.length()}")
                } catch (Exception e) {
                    logger.error("Failed to read request body early: ${e.message}")
                }
            }
            
            if (authzHeader != null && authzHeader.length() > 6 && authzHeader.startsWith("Basic ")) {
                String basicAuthEncoded = authzHeader.substring(6).trim()
                String basicAuthAsString = new String(basicAuthEncoded.decodeBase64())
                int indexOfColon = basicAuthAsString.indexOf(":")
                if (indexOfColon > 0) {
                    String username = basicAuthAsString.substring(0, indexOfColon)
                    String password = basicAuthAsString.substring(indexOfColon + 1)
                    try {
                        logger.info("LOGGING IN ${username} ${password}")
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
            
            // Re-enabled proper authentication - UserServices compilation issues resolved
            if (!authenticated || !ec.user?.userId) {
                logger.warn("Enhanced MCP authentication failed - no valid user authenticated")
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
                response.setContentType("application/json")
                response.setHeader("WWW-Authenticate", "Basic realm=\"Moqui MCP\"")
                response.writer.write(JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32003, message: "Authentication required. Use Basic auth with valid Moqui credentials."],
                    id: null
                ]))
                return
            }
            
            // Create Visit for JSON-RPC requests too
            def visit = null
            try {
                // Initialize web facade for Visit creation
                ec.initWebFacade(webappName, request, response)
                // Web facade was successful, get Visit it created
                visit = ec.user.getVisit()
                if (!visit) {
                    throw new Exception("Web facade succeeded but no Visit created")
                }
            } catch (Exception e) {
                logger.warn("Web facade initialization failed: ${e.message}, trying manual Visit creation")
                // Try to create Visit manually using the same pattern as handleSseConnection
                try {
                    def visitParams = [
                        sessionId: request.session.id,
                        webappName: webappName,
                        fromDate: new Timestamp(System.currentTimeMillis()),
                        initialLocale: request.locale.toString(),
                        initialRequest: (request.requestURL.toString() + (request.queryString ? "?" + request.queryString : "")).take(255),
                        initialReferrer: request.getHeader("Referer")?.take(255),
                        initialUserAgent: request.getHeader("User-Agent")?.take(255),
                        clientHostName: request.remoteHost,
                        clientUser: request.remoteUser,
                        serverIpAddress: ec.ecfi.getLocalhostAddress().getHostAddress(),
                        serverHostName: ec.ecfi.getLocalhostAddress().getHostName(),
                        clientIpAddress: request.remoteAddr,
                        userId: ec.user.userId,
                        userCreated: "Y"
                    ]
                    
                    logger.info("Creating Visit with params: ${visitParams}")
                    def visitResult = ec.service.sync().name("create", "moqui.server.Visit")
                        .parameters(visitParams)
                        .disableAuthz()
                        .call()
                    logger.info("Visit creation result: ${visitResult}")
                    
                    if (!visitResult || !visitResult.visitId) {
                        throw new Exception("Visit creation service returned null or no visitId")
                    }
                    
                    // Look up the actual Visit EntityValue
                    visit = ec.entity.find("moqui.server.Visit")
                        .condition("visitId", visitResult.visitId)
                        .disableAuthz()
                        .one()
                    if (!visit) {
                        throw new Exception("Failed to look up newly created Visit")
                    }
                    ec.web.session.setAttribute("moqui.visitId", visit.visitId)
                    logger.info("Manually created Visit ${visit.visitId} for user ${ec.user.username}")
                    
                } catch (Exception visitEx) {
                    logger.error("Manual Visit creation failed: ${visitEx.message}", visitEx)
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to create Visit")
                    return
                }
            }
            
            // Final check that we have a Visit
            if (!visit) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to create Visit")
                return
            }
            
            // Route based on request method and path
            String requestURI = request.getRequestURI()
            String method = request.getMethod()
            logger.info("Enhanced MCP Request: ${method} ${requestURI} - Content-Length: ${request.getContentLength()}")
            
            if ("GET".equals(method) && requestURI.endsWith("/sse")) {
                handleSseConnection(request, response, ec, webappName)
            } else if ("POST".equals(method) && requestURI.endsWith("/message")) {
                handleMessage(request, response, ec)
            } else if ("POST".equals(method) && (requestURI.equals("/mcp") || requestURI.endsWith("/mcp"))) {
                // Handle POST requests to /mcp for JSON-RPC
                logger.info("About to call handleJsonRpc with visit: ${visit?.visitId}")
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
            response.writer.write(JsonOutput.toJson([
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
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32002, message: "Too Many Requests: " + e.message],
                id: null
            ]))
        } catch (Throwable t) {
            logger.error("Error in Enhanced MCP request", t)
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32603, message: "Internal error: " + t.message],
                id: null
            ]))
        } finally {
            ec.destroy()
        }
    }
    
    private void handleSseConnection(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec, String webappName) 
            throws IOException {
        
        logger.info("Handling Enhanced SSE connection from ${request.remoteAddr}")
        
        // Check for existing session ID first
        String sessionId = request.getHeader("Mcp-Session-Id")
        def visit = null
        
        // If we have a session ID, try to find existing Visit
        if (sessionId) {
            try {
                visit = ec.entity.find("moqui.server.Visit")
                    .condition("visitId", sessionId)
                    .disableAuthz()
                    .one()
                
                if (visit) {
                    // Verify user has access to this Visit
                    if (!visit.userId || !ec.user.userId || visit.userId.toString() != ec.user.userId.toString()) {
                        logger.warn("Visit userId ${visit.userId} doesn't match current user userId ${ec.user.userId} - access denied")
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied for session: " + sessionId)
                        return
                    }
                    
                    // Set existing visit ID in HTTP session
                    request.session.setAttribute("moqui.visitId", sessionId)
                    logger.info("Reusing existing Visit ${sessionId} for user ${ec.user.username}")
                } else {
                    logger.warn("Session ID ${sessionId} not found, will create new Visit")
                }
            } catch (Exception e) {
                logger.warn("Error looking up existing session ${sessionId}: ${e.message}")
            }
        }
        
        // Only create new Visit if we didn't find an existing one
        if (!visit) {
            // Initialize web facade for Visit creation, but avoid screen resolution
            // Modify request path to avoid ScreenResourceNotFoundException
            String originalRequestURI = request.getRequestURI()
            String originalPathInfo = request.getPathInfo()
            request.setAttribute("javax.servlet.include.request_uri", "/mcp")
            request.setAttribute("javax.servlet.include.path_info", "")
            
            try {
                ec.initWebFacade(webappName, request, response)
                // Web facade was successful, get the Visit it created
                visit = ec.user.getVisit()
                if (!visit) {
                    throw new Exception("Web facade succeeded but no Visit created")
                }
                logger.info("Created new Visit ${visit.visitId} for user ${ec.user.username}")
            } catch (Exception e) {
                logger.warn("Web facade initialization failed: ${e.message}, trying manual Visit creation")
                // Try to create Visit manually using the same pattern as UserFacadeImpl
                try {
                    def visitParams = [
                        sessionId: request.session.id,
                        webappName: webappName,
                        fromDate: new Timestamp(System.currentTimeMillis()),
                        initialLocale: request.locale.toString(),
                        initialRequest: (request.requestURL.toString() + (request.queryString ? "?" + request.queryString : "")).take(255),
                        initialReferrer: request.getHeader("Referer")?.take(255),
                        initialUserAgent: request.getHeader("User-Agent")?.take(255),
                        clientHostName: request.remoteHost,
                        clientUser: request.remoteUser,
                        serverIpAddress: ec.ecfi.getLocalhostAddress().getHostAddress(),
                        serverHostName: ec.ecfi.getLocalhostAddress().getHostName(),
                        clientIpAddress: request.remoteAddr,
                        userId: ec.user.userId,
                        userCreated: "Y"
                    ]
                    
                    logger.info("Creating Visit with params: ${visitParams}")
                    def visitResult = ec.service.sync().name("create", "moqui.server.Visit")
                        .parameters(visitParams)
                        .disableAuthz()
                        .call()
                    logger.info("Visit creation result: ${visitResult}")
                    
                    if (!visitResult || !visitResult.visitId) {
                        throw new Exception("Visit creation service returned null or no visitId")
                    }
                    
                    // Look up the actual Visit EntityValue
                    visit = ec.entity.find("moqui.server.Visit")
                        .condition("visitId", visitResult.visitId)
                        .disableAuthz()
                        .one()
                    if (!visit) {
                        throw new Exception("Failed to look up newly created Visit")
                    }
                    ec.web.session.setAttribute("moqui.visitId", visit.visitId)
                    logger.info("Manually created Visit ${visit.visitId} for user ${ec.user.username}")
                    
                } catch (Exception visitEx) {
                    logger.error("Manual Visit creation failed: ${visitEx.message}", visitEx)
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to create Visit")
                    return
                }
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
                logger.info("No Mcp-Session-Id header detected, assuming old HTTP+SSE transport")
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
            logger.info("Set Mcp-Session-Id header to ${visit.visitId} for SSE connection")
            
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
    
    private void handleMessage(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec) 
            throws IOException {
        
        // Get sessionId from request parameter or header
        String sessionId = request.getParameter("sessionId") ?: request.getHeader("Mcp-Session-Id")
        if (!sessionId) {
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.writer.write(JsonOutput.toJson([
                error: "Missing sessionId parameter or header",
                architecture: "Visit-based sessions"
            ]))
            return
        }
        
        // Get Visit directly - this is our session
        def visit = ec.entity.find("moqui.server.Visit")
            .condition("visitId", sessionId)
            .disableAuthz()
            .one()
        
        if (!visit) {
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.setStatus(HttpServletResponse.SC_NOT_FOUND)
            response.writer.write(JsonOutput.toJson([
                error: "Session not found: " + sessionId,
                architecture: "Visit-based sessions"
            ]))
            return
        }
        
        // Verify user has access to this Visit - rely on Moqui security
        logger.info("Session validation: visit.userId=${visit.userId}, ec.user.userId=${ec.user.userId}, ec.user.username=${ec.user.username}")
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
                response.writer.write(JsonOutput.toJson([
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
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                id: rpcRequest.id,
                result: [status: "processed", sessionId: sessionId, architecture: "Visit-based"]
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
        
        // Initialize web facade for proper session management (like SSE connections)
        // This prevents the null user loop by ensuring HTTP session is properly linked
        try {
            ec.initWebFacade(webappName, request, response)
            logger.debug("JSON-RPC web facade initialized for user: ${ec.user?.username}")
        } catch (Exception e) {
            logger.warn("JSON-RPC web facade initialization failed: ${e.message}")
            // Continue anyway - we may still have basic user context from auth
        }
        
        String method = request.getMethod()
        String acceptHeader = request.getHeader("Accept")
        String contentType = request.getContentType()
        
        logger.info("Enhanced MCP JSON-RPC Request: ${method} ${request.requestURI} - Accept: ${acceptHeader}, Content-Type: ${contentType}")
        
        // Validate Accept header per MCP 2025-11-25 spec requirement #2
        // Client MUST include Accept header listing both application/json and text/event-stream
        if (!acceptHeader || !(acceptHeader.contains("application/json") || acceptHeader.contains("text/event-stream"))) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32600, message: "Accept header must include application/json and/or text/event-stream per MCP 2025-11-25 spec"],
                id: null
            ]))
            return
        }
        
        if (!"POST".equals(method)) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32601, message: "Method Not Allowed. Use POST for JSON-RPC or GET /mcp-sse/sse for SSE."],
                id: null
            ]))
            return
        }
        
        // Use pre-read request body
        logger.info("Using pre-read request body, length: ${requestBody?.length()}")
        
        String jsonMethod = request.getMethod()
        String jsonAcceptHeader = request.getHeader("Accept")
        String jsonContentType = request.getContentType()
        
        logger.info("Enhanced MCP JSON-RPC Request: ${jsonMethod} ${request.requestURI} - Accept: ${jsonAcceptHeader}, Content-Type: ${jsonContentType}")
        
        // Handle POST requests for JSON-RPC
        if (!"POST".equals(jsonMethod)) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32601, message: "Method Not Allowed. Use POST for JSON-RPC or GET /mcp-sse/sse for SSE."],
                id: null
            ]))
            return
        }
        
        // Use pre-read request body
        logger.info("Using pre-read request body, length: ${requestBody?.length()}")
        
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
            logger.info("MCP JSON-RPC request body: ${requestBody}")
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
        logger.info("Session ID from header: '${sessionId}', method: '${rpcRequest.method}'")
        
        // For initialize and notifications/initialized methods, use visit ID as session ID if no header
        if (!sessionId && ("initialize".equals(rpcRequest.method) || "notifications/initialized".equals(rpcRequest.method)) && visit) {
            sessionId = visit.visitId
            logger.info("${rpcRequest.method} method: using visit ID as session ID: ${sessionId}")
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
                def existingVisit = ec.entity.find("moqui.server.Visit")
                    .condition("visitId", sessionId)
                    .disableAuthz()
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
                logger.info("Set existing Visit ${sessionId} in HTTP session for user ${ec.user.username}")
                
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
            }
        }
        
        // Check if this is a notification (no id) - notifications get empty response
        boolean isNotification = !rpcRequest.containsKey('id')
        
        if (isNotification) {
            // Special handling for notifications/initialized to transition session state
            if ("notifications/initialized".equals(rpcRequest.method)) {
                logger.info("Processing notifications/initialized for sessionId: ${sessionId}")
                if (sessionId) {
                    sessionStates.put(sessionId, STATE_INITIALIZED)
                    logger.info("Session ${sessionId} transitioned to INITIALIZED state")
                }
                
                // For notifications/initialized, return 202 Accepted per MCP HTTP Streaming spec
                if (sessionId) {
                    response.setHeader("Mcp-Session-Id", sessionId.toString())
                }
                response.setStatus(HttpServletResponse.SC_ACCEPTED)  // 202 Accepted
                logger.info("Sent 202 Accepted response for notifications/initialized")
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
        
        // Set Mcp-Session-Id header BEFORE any response data (per MCP 2025-06-18 spec)
        // For initialize method, always use sessionId we have (from visit or header)
        String responseSessionId = null
        if (rpcRequest.method == "initialize" && sessionId) {
            responseSessionId = sessionId.toString()
        } else if (result?.sessionId) {
            responseSessionId = result.sessionId.toString()
        } else if (sessionId) {
            // For other methods, ensure we always return the session ID from header
            responseSessionId = sessionId.toString()
        }
        
        if (responseSessionId) {
            response.setHeader("Mcp-Session-Id", responseSessionId)
            logger.info("Set Mcp-Session-Id header to ${responseSessionId} for method ${rpcRequest.method}")
        }
        
        // Build JSON-RPC response for regular requests
        // Extract the actual result from Moqui service response
        def actualResult = result?.result ?: result
        def rpcResponse = [
            jsonrpc: "2.0",
            id: rpcRequest.id,
            result: actualResult
        ]
        
        // Check for pending server notifications and include them in response
        if (sessionId && notificationQueues.containsKey(sessionId)) {
            def pendingNotifications = notificationQueues.get(sessionId)
            if (pendingNotifications && !pendingNotifications.isEmpty()) {
                rpcResponse.notifications = pendingNotifications
                // Clear delivered notifications
                notificationQueues.put(sessionId, [])
                logger.info("Delivered ${pendingNotifications.size()} pending notifications to session ${sessionId}")
            }
        }
        
        response.setContentType("application/json")
        response.setCharacterEncoding("UTF-8")
        
        response.writer.write(JsonOutput.toJson(rpcResponse))
    }
    
    private Map<String, Object> processMcpMethod(String method, Map params, ExecutionContextImpl ec, String sessionId, def visit) {
        logger.info("Enhanced METHOD: ${method} with sessionId: ${sessionId}")
        
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
                        logger.info("Initialize - using visitId: ${visit.visitId}, set state ${params.sessionId} to INITIALIZING")
                    } else {
                        logger.warn("Initialize - no visit available, using null sessionId")
                    }
                    params.actualUserId = ec.user.userId
                    logger.info("Initialize - actualUserId: ${params.actualUserId}, sessionId: ${params.sessionId}")
                    def serviceResult = callMcpService("mcp#Initialize", params, ec)
                    // Add sessionId to the response for mcp.sh compatibility
                    if (serviceResult && serviceResult.result) {
                        serviceResult.result.sessionId = params.sessionId
                    }
                    return serviceResult
                case "ping":
                    // Simple ping for testing - bypass service for now
                    return [pong: System.currentTimeMillis(), sessionId: visit?.visitId, user: ec.user.username]
                case "tools/list":
                    // Ensure sessionId is available to service for notification consistency
                    if (sessionId) params.sessionId = sessionId
                    return callMcpService("mcp#ToolsList", params, ec)
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
                    logger.info("Tools list changed for sessionId: ${sessionId}")
                    // Could trigger cache invalidation here if needed
                    return null
                case "notifications/resources/list_changed":
                    // Handle resources list changed notification
                    logger.info("Resources list changed for sessionId: ${sessionId}")
                    // Could trigger cache invalidation here if needed
                    return null
                case "notifications/send":
                    // Handle notification sending
                    def notificationMethod = params?.method
                    def notificationParams = params?.params
                    if (!notificationMethod) {
                        throw new IllegalArgumentException("method is required for sending notification")
                    }
                    
                    logger.info("Sending notification ${notificationMethod} for sessionId: ${sessionId}")
                    
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
                        
                        logger.info("Notification queued for session ${sessionId}: ${notificationMethod}")
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
                    logger.info("Session ${sessionId} subscribed to: ${subscriptionMethod}")
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
                        logger.info("Session ${sessionId} unsubscribed from: ${subscriptionMethod}")
                    }
                    return [unsubscribed: true, sessionId: sessionId, method: subscriptionMethod]
                case "notifications/progress":
                    // Handle progress notification
                    def progressToken = params?.progressToken
                    def progressValue = params?.progress
                    def total = params?.total
                    logger.info("Progress notification for sessionId: ${sessionId}, token: ${progressToken}, progress: ${progressValue}/${total}")
                    // Store progress for potential polling
                    if (sessionId && progressToken) {
                        def progressKey = "${sessionId}_${progressToken}"
                        sessionProgress.put(progressKey, [progress: progressValue, total: total, timestamp: System.currentTimeMillis()])
                    }
                    return null
                case "notifications/resources/updated":
                    // Handle resource updated notification
                    def uri = params?.uri
                    logger.info("Resource updated notification for sessionId: ${sessionId}, uri: ${uri}")
                    // Could trigger resource cache invalidation here
                    return null
                case "notifications/prompts/list_changed":
                    // Handle prompts list changed notification
                    logger.info("Prompts list changed for sessionId: ${sessionId}")
                    // Could trigger prompt cache invalidation here
                    return null
                case "notifications/message":
                    // Handle general message notification
                    def level = params?.level ?: "info"
                    def message = params?.message
                    def data = params?.data
                    logger.info("Message notification for sessionId: ${sessionId}, level: ${level}, message: ${message}")
                    // Store message for potential retrieval
                    if (sessionId) {
                        def messages = sessionMessages.get(sessionId) ?: []
                        messages << [level: level, message: message, data: data, timestamp: System.currentTimeMillis()]
                        sessionMessages.put(sessionId, messages)
                    }
                    return null
                case "notifications/roots/list_changed":
                    // Handle roots list changed notification
                    logger.info("Roots list changed for sessionId: ${sessionId}")
                    // Could trigger roots cache invalidation here
                    return null
                case "logging/setLevel":
                    // Handle logging level change notification
                    logger.info("Logging level change requested for sessionId: ${sessionId}")
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
            def result = ec.service.sync().name("McpServices.${serviceName}")
                .parameters(params ?: [:])
                .call()
            
            logger.debug("Enhanced MCP service ${serviceName} result: ${result?.result?.size() ? 'result with ' + (result.result?.tools?.size() ?: 0) + ' tools' : 'empty result'}")
            if (result == null) {
                logger.error("Enhanced MCP service ${serviceName} returned null result")
                return [error: "Service returned null result"]
            }
            // Service framework returns result in 'result' field when out-parameters are used
            // Return the entire service result to maintain proper JSON-RPC structure
            // The MCP services already set the correct 'result' structure
            return result ?: [error: "Service returned null result"]
        } catch (Exception e) {
            logger.error("Error calling Enhanced MCP service ${serviceName}", e)
            return [error: e.message]
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
        
        // Also try to send via SSE if active connection exists
        def writer = activeConnections.get(sessionId)
        if (writer && !writer.checkError()) {
            try {
                sendSseEvent(writer, "notification", JsonOutput.toJson(notification), System.currentTimeMillis())
                logger.info("Sent notification via SSE to session ${sessionId}")
            } catch (Exception e) {
                logger.warn("Failed to send notification via SSE to session ${sessionId}: ${e.message}")
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
            // Look up all MCP Visits (persistent)
            def mcpVisits = ec.entity.find("moqui.server.Visit")
                .condition("initialRequest", "like", "%mcpSession%")
                .disableAuthz()
                .list()
            
            logger.info("Broadcasting to ${mcpVisits.size()} MCP visits, ${activeConnections.size()} active connections")
            
            int successCount = 0
            int failureCount = 0
            
            // Send to active connections (transient)
            mcpVisits.each { visit ->
                PrintWriter writer = activeConnections.get(visit.visitId)
                if (writer && !writer.checkError()) {
                    try {
                        sendSseEvent(writer, "broadcast", message.toJson())
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
            // Remove broken connection
            activeConnections.remove(sessionId)
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