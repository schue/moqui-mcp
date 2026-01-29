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
import org.moqui.mcp.adapter.McpSessionAdapter
import org.moqui.mcp.adapter.McpSession
import org.moqui.mcp.adapter.McpToolAdapter
import org.moqui.mcp.adapter.MoquiNotificationMcpBridge
import org.moqui.mcp.transport.SseTransport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Enhanced MCP Servlet with adapter-based architecture.
 * Uses adapters for session management, tool dispatch, and notifications.
 * This servlet acts as an orchestrator, delegating to specialized adapters.
 */
class EnhancedMcpServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(EnhancedMcpServlet.class)

    private JsonSlurper jsonSlurper = new JsonSlurper()

    // Adapter instances
    private McpSessionAdapter sessionAdapter
    private McpToolAdapter toolAdapter
    private SseTransport transport
    private MoquiNotificationMcpBridge notificationBridge

    // Visit cache to reduce database access and prevent lock contention
    private final Map<String, EntityValue> visitCache = new java.util.concurrent.ConcurrentHashMap<>()

    // Throttled session activity tracking
    private final Map<String, Long> lastActivityUpdate = new java.util.concurrent.ConcurrentHashMap<>()
    private static final long ACTIVITY_UPDATE_INTERVAL_MS = 30000 // 30 seconds

    // Configuration parameters
    private String sseEndpoint = "/sse"
    private String messageEndpoint = "/message"
    private int keepAliveIntervalSeconds = 30
    private int maxConnections = 100

    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)

        // Initialize adapters
        sessionAdapter = new McpSessionAdapter()
        toolAdapter = new McpToolAdapter()
        transport = new SseTransport(sessionAdapter)

        // Initialize notification bridge
        notificationBridge = new MoquiNotificationMcpBridge()

        // Read configuration from servlet init parameters
        sseEndpoint = config.getInitParameter("sseEndpoint") ?: sseEndpoint
        messageEndpoint = config.getInitParameter("messageEndpoint") ?: messageEndpoint
        keepAliveIntervalSeconds = config.getInitParameter("keepAliveIntervalSeconds")?.toInteger() ?: keepAliveIntervalSeconds
        maxConnections = config.getInitParameter("maxConnections")?.toInteger() ?: maxConnections

        String webappName = config.getInitParameter("moqui-name") ?:
            config.getServletContext().getInitParameter("moqui-name")

        // Register servlet instance in context for service access
        config.getServletContext().setAttribute("enhancedMcpServlet", this)

        // Get ECF and register notification bridge
        ExecutionContextFactoryImpl ecfi =
            (ExecutionContextFactoryImpl) config.getServletContext().getAttribute("executionContextFactory")
        if (ecfi) {
            notificationBridge.init(ecfi)
            notificationBridge.setTransport(transport)
            ecfi.registerNotificationMessageListener(notificationBridge)
            logger.info("Registered MoquiNotificationMcpBridge with ECF")
        }

        logger.info("EnhancedMcpServlet initialized with adapter architecture for webapp ${webappName}")
        logger.info("SSE endpoint: ${sseEndpoint}, Message endpoint: ${messageEndpoint}")
        logger.info("Keep-alive interval: ${keepAliveIntervalSeconds}s, Max connections: ${maxConnections}")
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
        if (handleCors(request, response)) return

        long startTime = System.currentTimeMillis()

        if (logger.traceEnabled) {
            logger.trace("Start Enhanced MCP request to [${request.getPathInfo()}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")
        }

        ExecutionContextImpl ec = ecfi.activeContext.get()
        if (ec == null) {
            logger.warn("No ExecutionContext found from MoquiAuthFilter, creating new one")
            ec = ecfi.getEci()
        }

        try {
            // Read request body early before any other processing can consume it
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
                handleJsonRpc(request, response, ec, webappName, requestBody, visit)
            } else if ("GET".equals(method) && (requestURI.equals("/mcp") || requestURI.endsWith("/mcp"))) {
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
            def errorMsg = t.message?.toString() ?: "Unknown error"
            response.writer.write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error: ${errorMsg.replace("\"", "\\\"")}\"},\"id\":null}")
        }
    }

    private void handleSseConnection(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec, String webappName)
            throws IOException {

        logger.debug("Handling Enhanced SSE connection from ${request.remoteAddr}")

        // Check for existing session ID
        String sessionId = request.getHeader("Mcp-Session-Id")
        def visit = null
        String userId = ec.user.userId?.toString()

        // If we have a session ID, validate it
        if (sessionId) {
            def session = sessionAdapter.getSession(sessionId)
            if (session) {
                // Verify user has access
                if (session.userId != userId) {
                    logger.warn("Session userId ${session.userId} doesn't match current user ${userId} - access denied")
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied for session: " + sessionId)
                    return
                }
                visit = getCachedVisit(ec, sessionId)
            } else {
                logger.warn("Session not found: ${sessionId}")
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Session not found: " + sessionId)
                return
            }
        }

        // Create new Visit/session if needed
        if (!visit) {
            try {
                ec.initWebFacade(webappName, request, response)
                visit = ec.user.getVisit()
                if (!visit) {
                    throw new Exception("Web facade succeeded but no Visit created")
                }

                // Create session in adapter with authenticated userId
                sessionId = visit.visitId?.toString()
                sessionAdapter.createSession(sessionId, ec.user.userId?.toString())
                logger.info("Created new session ${sessionId} for user ${ec.user.username}")

            } catch (Exception e) {
                logger.error("Failed to create session: ${e.message}", e)
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to create session")
                return
            }
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
        response.setHeader("X-Accel-Buffering", "no")
        response.setHeader("Mcp-Session-Id", sessionId)

        // Register SSE writer with transport
        transport.registerSseWriter(sessionId, response.writer)

        try {
            // Send endpoint event for backwards compatibility
            if (!request.getHeader("Mcp-Session-Id")) {
                transport.sendSseEventWithId(response.writer, "endpoint", "/mcp", 0)
            }

            // Send connect event
            def connectData = [
                version: "2.0.2",
                protocolVersion: "2025-06-18",
                architecture: "Adapter-based MCP with session registry"
            ]
            transport.sendSseEventWithId(response.writer, "connect", JsonOutput.toJson(connectData), 1)

            // Deliver any queued notifications
            transport.deliverQueuedNotifications(sessionId)

            // Keep connection alive with periodic pings
            int pingCount = 0
            while (!response.isCommitted() && pingCount < 60) {
                Thread.sleep(5000)

                if (!response.isCommitted()) {
                    if (!transport.sendPing(sessionId)) {
                        logger.debug("Ping failed for session ${sessionId}, ending SSE loop")
                        break
                    }
                    pingCount++

                    // Update session activity throttled
                    if (pingCount % 6 == 0) {
                        updateSessionActivityThrottled(sessionId)
                    }
                }
            }

        } catch (InterruptedException e) {
            logger.info("SSE connection interrupted for session ${sessionId}")
            Thread.currentThread().interrupt()
        } catch (Exception e) {
            logger.warn("Enhanced SSE connection error: ${e.message}", e)
        } finally {
            // Clean up
            transport.unregisterSseWriter(sessionId)

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
        def session = sessionAdapter.getSession(sessionId)

        if (!session) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Session not found: " + sessionId)
            return
        }

        // Verify user has access
        if (session.userId != ec.user.userId?.toString()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                error: "Access denied for session: " + sessionId
            ]))
            return
        }

        try {
            if (!requestBody || !requestBody.trim()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
                response.setContentType("application/json")
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
                    id: rpcRequest?.id ?: null
                ]))
                return
            }

            // Process method with session context
            def result = processMcpMethod(rpcRequest.method, rpcRequest.params, ec, sessionId, null)

            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.setStatus(HttpServletResponse.SC_OK)

            def actualResult = result?.result ?: result
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                id: rpcRequest.id,
                result: actualResult
            ]))

        } catch (Exception e) {
            logger.error("Error processing message for session ${sessionId}: ${e.message}", e)
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            response.setContentType("application/json")
            response.writer.write(JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32603, message: "Internal error: " + e.message],
                id: null
            ]))
        }
    }

    private void handleJsonRpc(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec, String webappName, String requestBody, def visit)
            throws IOException {

        String method = request.getMethod()
        String acceptHeader = request.getHeader("Accept")

        // Validate Accept header per MCP spec
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

        def rpcRequest
        try {
            rpcRequest = jsonSlurper.parseText(requestBody)
        } catch (Exception e) {
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

        // Validate MCP protocol version
        String protocolVersion = request.getHeader("MCP-Protocol-Version")
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

        // Get session ID from header
        String sessionId = request.getHeader("Mcp-Session-Id")

        // For initialize, use visit ID as session ID
        if (!sessionId && ("initialize".equals(rpcRequest.method) || "notifications/initialized".equals(rpcRequest.method)) && visit) {
            sessionId = visit.visitId?.toString()
        }

        // Validate session ID for non-initialize requests
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

        // For existing sessions, validate ownership
        if (sessionId && rpcRequest.method != "initialize") {
            def session = sessionAdapter.getSession(sessionId)
            if (!session) {
                // Try loading from database
                def existingVisit = getCachedVisit(ec, sessionId)
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

                // Verify ownership
                if (existingVisit.userId?.toString() != ec.user.userId?.toString()) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN)
                    response.setContentType("application/json")
                    response.writer.write(JsonOutput.toJson([
                        jsonrpc: "2.0",
                        error: [code: -32600, message: "Access denied for session: ${sessionId}"],
                        id: rpcRequest.id
                    ]))
                    return
                }

                // Create session in adapter if not exists
                if (!sessionAdapter.hasSession(sessionId)) {
                    sessionAdapter.createSession(sessionId, ec.user.userId?.toString())
                }
            } else if (session.userId != ec.user.userId?.toString()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN)
                response.setContentType("application/json")
                response.writer.write(JsonOutput.toJson([
                    jsonrpc: "2.0",
                    error: [code: -32600, message: "Access denied for session: ${sessionId}"],
                    id: rpcRequest.id
                ]))
                return
            }
        }

        // Check if this is a notification (no id)
        boolean isNotification = !rpcRequest.containsKey('id')

        if (isNotification) {
            if ("notifications/initialized".equals(rpcRequest.method)) {
                if (sessionId) {
                    sessionAdapter.setSessionState(sessionId, McpSession.STATE_INITIALIZED)
                    logger.debug("Session ${sessionId} transitioned to INITIALIZED state")
                }

                if (sessionId) {
                    response.setHeader("Mcp-Session-Id", sessionId)
                }
                response.setContentType("text/event-stream")
                response.setStatus(HttpServletResponse.SC_ACCEPTED)
                response.flushBuffer()
                return
            }

            // Other notifications receive 204 No Content
            if (sessionId) {
                response.setHeader("Mcp-Session-Id", sessionId)
            }
            response.setStatus(HttpServletResponse.SC_NO_CONTENT)
            response.flushBuffer()
            return
        }

        // Process MCP method
        def result = processMcpMethod(rpcRequest.method, rpcRequest.params, ec, sessionId, visit)

        // Update session activity
        if (sessionId && !"ping".equals(rpcRequest.method) && !"tools/list".equals(rpcRequest.method)) {
            updateSessionActivityThrottled(sessionId)
        }

        // Set session header
        String responseSessionId = null
        if (rpcRequest.method == "initialize" && sessionId) {
            responseSessionId = sessionId
        } else if (result?.sessionId) {
            responseSessionId = result.sessionId?.toString()
        } else if (sessionId) {
            responseSessionId = sessionId
        }

        if (responseSessionId) {
            response.setHeader("Mcp-Session-Id", responseSessionId)
        }

        // Build response
        def actualResult = result?.result ?: result
        def rpcResponse = [
            jsonrpc: "2.0",
            id: rpcRequest.id,
            result: actualResult
        ]

        response.setContentType("application/json")
        response.setCharacterEncoding("UTF-8")
        response.writer.write(JsonOutput.toJson(rpcResponse))
    }

    private Map<String, Object> processMcpMethod(String method, Map params, ExecutionContextImpl ec, String sessionId, def visit) {
        logger.debug("Processing MCP method: ${method} with sessionId: ${sessionId}")

        try {
            if (params == null) params = [:]
            params.sessionId = visit?.visitId ?: sessionId

            // Check session state for methods that require initialization
            def session = sessionId ? sessionAdapter.getSession(sessionId) : null
            if (!["initialize", "ping"].contains(method)) {
                if (!session || session.state != McpSession.STATE_INITIALIZED) {
                    logger.warn("Method ${method} called but session ${sessionId} not initialized")
                    return [error: "Session not initialized. Call initialize first, then send notifications/initialized."]
                }
            }

            switch (method) {
                case "initialize":
                    if (visit && visit.visitId) {
                        params.sessionId = visit.visitId
                        // Create session in adapter with actual authenticated userId
                        if (!sessionAdapter.hasSession(params.sessionId?.toString())) {
                            sessionAdapter.createSession(params.sessionId?.toString(), ec.user.userId?.toString())
                        }
                        sessionAdapter.setSessionState(params.sessionId?.toString(), McpSession.STATE_INITIALIZING)
                    }
                    params.actualUserId = ec.user.userId
                    def serviceResult = callMcpService("mcp#Initialize", params, ec)
                    if (serviceResult && !serviceResult.error) {
                        serviceResult.sessionId = params.sessionId
                        sessionAdapter.setSessionState(params.sessionId?.toString(), McpSession.STATE_INITIALIZED)
                    }
                    return serviceResult

                case "ping":
                    return [pong: System.currentTimeMillis(), sessionId: visit?.visitId, user: ec.user.username]

                case "tools/list":
                    if (sessionId) params.sessionId = sessionId
                    return callMcpService("list#Tools", params, ec)

                case "tools/call":
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

                case "notifications/tools/list_changed":
                case "notifications/resources/list_changed":
                case "notifications/prompts/list_changed":
                case "notifications/roots/list_changed":
                case "logging/setLevel":
                    logger.debug("Notification ${method} for sessionId: ${sessionId}")
                    return null

                case "notifications/send":
                    def notificationMethod = params?.method
                    def notificationParams = params?.params
                    if (!notificationMethod) {
                        throw new IllegalArgumentException("method is required for sending notification")
                    }
                    if (sessionId) {
                        def notification = [
                            jsonrpc: "2.0",
                            method: notificationMethod,
                            params: notificationParams
                        ]
                        transport.sendNotification(sessionId, notification)
                    }
                    return [sent: true, sessionId: sessionId, method: notificationMethod]

                case "notifications/subscribe":
                    def subscriptionMethod = params?.method
                    if (!sessionId || !subscriptionMethod) {
                        throw new IllegalArgumentException("sessionId and method are required for subscription")
                    }
                    session?.subscriptions?.add(subscriptionMethod)
                    return [subscribed: true, sessionId: sessionId, method: subscriptionMethod]

                case "notifications/unsubscribe":
                    def subscriptionMethod = params?.method
                    if (!sessionId || !subscriptionMethod) {
                        throw new IllegalArgumentException("sessionId and method are required for unsubscription")
                    }
                    session?.subscriptions?.remove(subscriptionMethod)
                    return [unsubscribed: true, sessionId: sessionId, method: subscriptionMethod]

                case "notifications/progress":
                    def progressToken = params?.progressToken
                    def progressValue = params?.progress
                    def total = params?.total
                    logger.debug("Progress notification: ${progressToken}, ${progressValue}/${total}")
                    return null

                case "notifications/resources/updated":
                    logger.debug("Resource updated: ${params?.uri}")
                    return null

                case "notifications/message":
                    def level = params?.level ?: "info"
                    def message = params?.message
                    logger.debug("Message notification: level=${level}, message=${message}")
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
        logger.debug("Calling MCP service: ${serviceName}")

        try {
            ec.artifactExecution.disableAuthz()
            def result = ec.service.sync().name("McpServices.${serviceName}")
                .parameters(params ?: [:])
                .call()

            if (result == null) {
                return [error: "Service returned null result"]
            }

            if (result?.containsKey('result')) {
                return result.result
            }
            return result

        } catch (Exception e) {
            logger.error("Error calling MCP service ${serviceName}", e)
            return [error: e.message]
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    private EntityValue getCachedVisit(ExecutionContextImpl ec, String sessionId) {
        if (!sessionId) return null

        EntityValue cachedVisit = visitCache.get(sessionId)
        if (cachedVisit != null) {
            return cachedVisit
        }

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

    private void updateSessionActivityThrottled(String sessionId) {
        if (!sessionId) return

        long now = System.currentTimeMillis()
        Long lastUpdate = lastActivityUpdate.get(sessionId)

        if (lastUpdate == null || (now - lastUpdate) > ACTIVITY_UPDATE_INTERVAL_MS) {
            Object sessionLock = sessionAdapter.getSessionLock(sessionId)
            synchronized (sessionLock) {
                lastUpdate = lastActivityUpdate.get(sessionId)
                if (lastUpdate == null || (now - lastUpdate) > ACTIVITY_UPDATE_INTERVAL_MS) {
                    sessionAdapter.touchSession(sessionId)
                    lastActivityUpdate.put(sessionId, now)
                    logger.debug("Updated activity for session ${sessionId}")
                }
            }
        }
    }

    private static boolean handleCors(HttpServletRequest request, HttpServletResponse response) {
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
     * Queue a notification for delivery to a session
     */
    void queueNotification(String sessionId, Map notification) {
        if (!sessionId || !notification) return
        transport.sendNotification(sessionId, notification)
    }

    /**
     * Send to a specific session
     */
    void sendToSession(String sessionId, Map message) {
        transport.sendMessage(sessionId, message)
    }

    /**
     * Get session statistics
     */
    Map getSessionStatistics() {
        def stats = transport.getStatistics()
        return stats + [
            maxConnections: maxConnections,
            endpoints: [
                sse: sseEndpoint,
                message: messageEndpoint
            ],
            keepAliveInterval: keepAliveIntervalSeconds
        ]
    }

    /**
     * Get the notification bridge for external access
     */
    MoquiNotificationMcpBridge getNotificationBridge() {
        return notificationBridge
    }

    /**
     * Get the transport for external access
     */
    SseTransport getTransport() {
        return transport
    }

    @Override
    void destroy() {
        logger.info("Destroying EnhancedMcpServlet")

        // Close all sessions
        for (String sessionId in sessionAdapter.getAllSessionIds()) {
            transport.closeSession(sessionId)
        }

        // Clean up notification bridge
        if (notificationBridge) {
            notificationBridge.destroy()
        }

        super.destroy()
    }
}
