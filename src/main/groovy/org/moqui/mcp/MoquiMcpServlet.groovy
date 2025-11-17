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
class MoquiMcpServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiMcpServlet.class)
    
    private JsonSlurper jsonSlurper = new JsonSlurper()
    
    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)
        String webappName = config.getInitParameter("moqui-name") ?: 
            config.getServletContext().getInitParameter("moqui-name")
        logger.info("MoquiMcpServlet initialized for webapp ${webappName}")
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
        
        // Handle CORS (following Moqui pattern)
        if (handleCors(request, response, webappName, ecfi)) return
        
        long startTime = System.currentTimeMillis()
        
        if (logger.traceEnabled) {
            logger.trace("Start MCP request to [${request.getPathInfo()}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")
        }
        
        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null) {
            logger.warn("In MoquiMcpServlet.service there is already an ExecutionContext for user ${activeEc.user.username}")
            activeEc.destroy()
        }
        
        ExecutionContextImpl ec = ecfi.getEci()
        
        try {
            // Initialize web facade for authentication but avoid screen system
            ec.initWebFacade(webappName, request, response)
            
            logger.info("MCP Request authenticated user: ${ec.user?.username}, userId: ${ec.user?.userId}")
            
            // If no user authenticated, try to authenticate as admin for MCP requests
            if (!ec.user?.userId) {
                logger.info("No user authenticated, attempting admin login for MCP")
                try {
                    ec.user.loginUser("admin", "admin")
                    logger.info("MCP Admin login successful, user: ${ec.user?.username}")
                } catch (Exception e) {
                    logger.warn("MCP Admin login failed: ${e.message}")
                }
            }
            
            // Handle MCP JSON-RPC protocol
            handleMcpRequest(request, response, ec)
            
        } catch (ArtifactAuthorizationException e) {
            logger.warn("MCP Access Forbidden (no authz): " + e.message)
            // Handle error directly without sendError to avoid Moqui error screen interference
            response.setStatus(HttpServletResponse.SC_FORBIDDEN)
            response.setContentType("application/json")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32001, message: "Access Forbidden: " + e.message],
                id: null
            ]))
        } catch (ArtifactTarpitException e) {
            logger.warn("MCP Too Many Requests (tarpit): " + e.message)
            // Handle error directly without sendError to avoid Moqui error screen interference
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
            logger.error("Error in MCP request", t)
            // Handle error directly without sendError to avoid Moqui error screen interference
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
    
    private void handleMcpRequest(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec) 
            throws IOException {
        
        String method = request.getMethod()
        String acceptHeader = request.getHeader("Accept")
        String contentType = request.getContentType()
        String userAgent = request.getHeader("User-Agent")
        
        logger.info("MCP Request: ${method} ${request.requestURI} - Accept: ${acceptHeader}, Content-Type: ${contentType}, User-Agent: ${userAgent}")
        
        // Handle SSE (Server-Sent Events) for streaming
        if ("GET".equals(method) && acceptHeader != null && acceptHeader.contains("text/event-stream")) {
            logger.info("Processing SSE request - GET with text/event-stream Accept header")
            handleSseRequest(request, response, ec)
            return
        }
        
        // Handle POST requests for JSON-RPC
        if (!"POST".equals(method)) {
            logger.warn("Rejecting non-POST request: ${method} - Only POST for JSON-RPC or GET with Accept: text/event-stream for SSE allowed")
            // Handle error directly without sendError to avoid Moqui error screen interference
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
            response.setContentType("application/json")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32601, message: "Method Not Allowed. Use POST for JSON-RPC or GET with Accept: text/event-stream for SSE."],
                id: null
            ]))
            return
        }
        
        // Read and parse JSON-RPC request following official MCP servlet pattern
        logger.info("Processing JSON-RPC POST request")
        
        String requestBody
        try {
            // Use BufferedReader pattern from official MCP servlet
            BufferedReader reader = request.reader
            StringBuilder body = new StringBuilder()
            String line
            while ((line = reader.readLine()) != null) {
                body.append(line)
            }
            
            requestBody = body.toString()
            logger.info("JSON-RPC request body (${requestBody.length()} chars): ${requestBody}")
            
        } catch (IOException e) {
            logger.error("Failed to read request body: ${e.message}")
            // Handle error directly without sendError to avoid Moqui error screen interference
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32700, message: "Failed to read request body: " + e.message],
                id: null
            ]))
            return
        }
        
        if (!requestBody) {
            logger.warn("Empty request body in JSON-RPC POST request")
            // Handle error directly without sendError to avoid Moqui error screen interference
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
            logger.info("Parsed JSON-RPC request: method=${rpcRequest.method}, id=${rpcRequest.id}")
        } catch (Exception e) {
            logger.error("Failed to parse JSON-RPC request: ${e.message}")
            // Handle error directly without sendError to avoid Moqui error screen interference
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32700, message: "Invalid JSON: " + e.message],
                id: null
            ]))
            return
        }
        
        // Validate JSON-RPC 2.0 basic structure
        if (!rpcRequest?.jsonrpc || rpcRequest.jsonrpc != "2.0" || !rpcRequest?.method) {
            logger.warn("Invalid JSON-RPC 2.0 structure: jsonrpc=${rpcRequest?.jsonrpc}, method=${rpcRequest?.method}")
            // Handle error directly without sendError to avoid Moqui error screen interference
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.writer.write(groovy.json.JsonOutput.toJson([
                jsonrpc: "2.0",
                error: [code: -32600, message: "Invalid JSON-RPC 2.0 request"],
                id: null
            ]))
            return
        }
        
        // Process MCP method
        logger.info("Calling processMcpMethod with method: ${rpcRequest.method}, params: ${rpcRequest.params}")
        def result = processMcpMethod(rpcRequest.method, rpcRequest.params, ec)
        logger.info("processMcpMethod returned result: ${result}")
        
        // Build JSON-RPC response
        def rpcResponse = [
            jsonrpc: "2.0",
            id: rpcRequest.id,
            result: result
        ]
        logger.info("Sending JSON-RPC response: ${rpcResponse}")
        
        // Send response following official MCP servlet pattern
        response.setContentType("application/json")
        response.setCharacterEncoding("UTF-8")
        response.writer.write(groovy.json.JsonOutput.toJson(rpcResponse))
    }
    
    private void handleSseRequest(HttpServletRequest request, HttpServletResponse response, ExecutionContextImpl ec) 
            throws IOException {
        
        logger.info("Handling SSE request from ${request.remoteAddr}")
        
        // Set SSE headers
        response.setContentType("text/event-stream")
        response.setCharacterEncoding("UTF-8")
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        
        // Send initial connection event
        response.writer.write("event: connect\n")
        response.writer.write("data: {\"type\":\"connected\",\"timestamp\":\"${System.currentTimeMillis()}\"}\n\n")
        response.writer.flush()
        
        // Keep connection alive with periodic pings
        long startTime = System.currentTimeMillis()
        int pingCount = 0
        
        try {
            while (!response.isCommitted() && pingCount < 10) { // Limit to 10 pings for testing
                Thread.sleep(5000) // Wait 5 seconds
                
                if (!response.isCommitted()) {
                    response.writer.write("event: ping\n")
                    response.writer.write("data: {\"type\":\"ping\",\"count\":${pingCount},\"timestamp\":\"${System.currentTimeMillis()}\"}\n\n")
                    response.writer.flush()
                    pingCount++
                }
            }
        } catch (Exception e) {
            logger.warn("SSE connection interrupted: ${e.message}")
        } finally {
            // Send close event
            if (!response.isCommitted()) {
                response.writer.write("event: close\n")
                response.writer.write("data: {\"type\":\"disconnected\",\"timestamp\":\"${System.currentTimeMillis()}\"}\n\n")
                response.writer.flush()
            }
        }
    }
    
    private Map<String, Object> processMcpMethod(String method, Map params, ExecutionContextImpl ec) {
        logger.info("METHOD: ${method} with params: ${params}")
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
        logger.info("Calling MCP service: ${serviceName} with params: ${params}")
        
        try {
            def result = ec.service.sync().name("org.moqui.mcp.McpServices.${serviceName}")
                .parameters(params ?: [:])
                .call()
            
            logger.info("MCP service ${serviceName} result: ${result}")
            return result.result
        } catch (Exception e) {
            logger.error("Error calling MCP service ${serviceName}", e)
            throw e
        }
    }
    
    private Map<String, Object> initializeMcp(Map params, ExecutionContextImpl ec) {
        logger.info("MCP Initialize called with params: ${params}")
        
        // Discover available tools and resources
        def toolsResult = listTools([:], ec)
        def resourcesResult = listResources([:], ec)
        
        def capabilities = [
            tools: [:],
            resources: [:],
            logging: [:]
        ]
        
        // Only include tools if we found any
        if (toolsResult?.tools) {
            capabilities.tools = [listChanged: true]
        }
        
        // Only include resources if we found any  
        if (resourcesResult?.resources) {
            capabilities.resources = [subscribe: true, listChanged: true]
        }
        
        def initResult = [
            protocolVersion: "2025-06-18",
            capabilities: capabilities,
            serverInfo: [
                name: "Moqui MCP Server",
                version: "2.0.0"
            ]
        ]
        
        logger.info("MCP Initialize returning: ${initResult}")
        return initResult
    }
    
    private Map<String, Object> pingMcp(Map params, ExecutionContextImpl ec) {
        logger.info("MCP Ping called with params: ${params}")
        
        return [
            result: "pong"
        ]
    }
    
    private Map<String, Object> listTools(Map params, ExecutionContextImpl ec) {
        // List available Moqui services as tools
        def tools = []
        
        // Entity services
        tools << [
            name: "EntityFind",
            description: "Find entities in Moqui",
            inputSchema: [
                type: "object",
                properties: [
                    entity: [type: "string", description: "Entity name"],
                    fields: [type: "array", description: "Fields to select"],
                    constraint: [type: "string", description: "Constraint expression"],
                    limit: [type: "number", description: "Maximum results"]
                ]
            ]
        ]
        
        tools << [
            name: "EntityCreate", 
            description: "Create entity records",
            inputSchema: [
                type: "object",
                properties: [
                    entity: [type: "string", description: "Entity name"],
                    fields: [type: "object", description: "Field values"]
                ]
            ]
        ]
        
        tools << [
            name: "EntityUpdate",
            description: "Update entity records", 
            inputSchema: [
                type: "object",
                properties: [
                    entity: [type: "string", description: "Entity name"],
                    fields: [type: "object", description: "Field values"],
                    constraint: [type: "string", description: "Constraint expression"]
                ]
            ]
        ]
        
        tools << [
            name: "EntityDelete",
            description: "Delete entity records",
            inputSchema: [
                type: "object", 
                properties: [
                    entity: [type: "string", description: "Entity name"],
                    constraint: [type: "string", description: "Constraint expression"]
                ]
            ]
        ]
        
        // Service execution tools
        tools << [
            name: "ServiceCall",
            description: "Execute Moqui services",
            inputSchema: [
                type: "object",
                properties: [
                    service: [type: "string", description: "Service name (verb:noun)"],
                    parameters: [type: "object", description: "Service parameters"]
                ]
            ]
        ]
        
        // User management tools
        tools << [
            name: "UserFind",
            description: "Find users in the system",
            inputSchema: [
                type: "object",
                properties: [
                    username: [type: "string", description: "Username filter"],
                    email: [type: "string", description: "Email filter"],
                    enabled: [type: "boolean", description: "Filter by enabled status"]
                ]
            ]
        ]
        
        // Party management tools
        tools << [
            name: "PartyFind",
            description: "Find parties (organizations, persons)",
            inputSchema: [
                type: "object",
                properties: [
                    partyType: [type: "string", description: "Party type (PERSON, ORGANIZATION)"],
                    partyName: [type: "string", description: "Party name filter"],
                    status: [type: "string", description: "Status filter"]
                ]
            ]
        ]
        
        // Order management tools
        tools << [
            name: "OrderFind",
            description: "Find sales orders",
            inputSchema: [
                type: "object",
                properties: [
                    orderId: [type: "string", description: "Order ID"],
                    customerId: [type: "string", description: "Customer party ID"],
                    status: [type: "string", description: "Order status"],
                    fromDate: [type: "string", description: "From date (YYYY-MM-DD)"],
                    thruDate: [type: "string", description: "Thru date (YYYY-MM-DD)"]
                ]
            ]
        ]
        
        // Product management tools
        tools << [
            name: "ProductFind",
            description: "Find products",
            inputSchema: [
                type: "object",
                properties: [
                    productId: [type: "string", description: "Product ID"],
                    productName: [type: "string", description: "Product name filter"],
                    productType: [type: "string", description: "Product type"],
                    category: [type: "string", description: "Product category"]
                ]
            ]
        ]
        
        // Inventory tools
        tools << [
            name: "InventoryCheck",
            description: "Check product inventory levels",
            inputSchema: [
                type: "object",
                properties: [
                    productId: [type: "string", description: "Product ID"],
                    facilityId: [type: "string", description: "Facility ID"],
                    locationId: [type: "string", description: "Location ID"]
                ]
            ]
        ]
        
        // System status tools
        tools << [
            name: "SystemStatus",
            description: "Get system status and statistics",
            inputSchema: [
                type: "object",
                properties: [
                    includeMetrics: [type: "boolean", description: "Include performance metrics"],
                    includeCache: [type: "boolean", description: "Include cache statistics"]
                ]
            ]
        ]
        
        return [tools: tools]
    }
    
    private Map<String, Object> callTool(Map params, ExecutionContextImpl ec) {
        String toolName = params.name as String
        Map arguments = params.arguments as Map ?: [:]
        
        logger.info("Calling tool via service: ${toolName} with arguments: ${arguments}")
        
        try {
            // Use the existing McpServices.mcp#ToolsCall service
            def result = ec.service.sync().name("org.moqui.mcp.McpServices.mcp#ToolsCall")
                .parameters([name: toolName, arguments: arguments])
                .call()
            
            logger.info("Tool call result: ${result}")
            return result.result
        } catch (Exception e) {
            logger.error("Error calling tool ${toolName} via service", e)
            return [
                content: [[type: "text", text: "Error: " + e.message]],
                isError: true
            ]
        }
    }
    
    private Map<String, Object> callEntityFind(Map arguments, ExecutionContextImpl ec) {
        String entity = arguments.entity as String
        List<String> fields = arguments.fields as List<String>
        String constraint = arguments.constraint as String
        Integer limit = arguments.limit as Integer
        
        def finder = ec.entity.find(entity).selectFields(fields ?: ["*"]).limit(limit ?: 100)
        if (constraint) {
            finder.condition(constraint)
        }
        def result = finder.list()
        
        return [
            content: [[type: "text", text: "Found ${result.size()} records: ${result}"]],
            isError: false
        ]
    }
    
    private Map<String, Object> callEntityCreate(Map arguments, ExecutionContextImpl ec) {
        String entity = arguments.entity as String
        Map fields = arguments.fields as Map
        
        def result = ec.entity.create(entity).setAll(fields).create()
        
        return [
            content: [[type: "text", text: "Created record: ${result}"]],
            isError: false
        ]
    }
    
    private Map<String, Object> callEntityUpdate(Map arguments, ExecutionContextImpl ec) {
        String entity = arguments.entity as String
        Map fields = arguments.fields as Map
        String constraint = arguments.constraint as String
        
        def updater = ec.entity.update(entity).setAll(fields)
        if (constraint) {
            updater.condition(constraint)
        }
        int updated = updater.update()
        
        return [
            content: [[type: "text", text: "Updated ${updated} records"]],
            isError: false
        ]
    }
    
    private Map<String, Object> callEntityDelete(Map arguments, ExecutionContextImpl ec) {
        String entity = arguments.entity as String
        String constraint = arguments.constraint as String
        
        def deleter = ec.entity.delete(entity)
        if (constraint) {
            deleter.condition(constraint)
        }
        int deleted = deleter.delete()
        
        return [
            content: [[type: "text", text: "Deleted ${deleted} records"]],
            isError: false
        ]
    }
    
    private Map<String, Object> callService(Map arguments, ExecutionContextImpl ec) {
        String serviceName = arguments.service as String
        Map parameters = arguments.parameters as Map ?: [:]
        
        try {
            def result = ec.service.sync().name(serviceName).parameters(parameters).call()
            return [
                content: [[type: "text", text: "Service ${serviceName} executed successfully. Result: ${result}"]],
                isError: false
            ]
        } catch (Exception e) {
            return [
                content: [[type: "text", text: "Error executing service ${serviceName}: ${e.message}"]],
                isError: true
            ]
        }
    }
    
    private Map<String, Object> callUserFind(Map arguments, ExecutionContextImpl ec) {
        String username = arguments.username as String
        String email = arguments.email as String
        Boolean enabled = arguments.enabled as Boolean
        
        def condition = new StringBuilder("1=1")
        def parameters = [:]
        
        if (username) {
            condition.append(" AND username = :username")
            parameters.username = username
        }
        if (email) {
            condition.append(" AND email_address = :email")
            parameters.email = email
        }
        if (enabled != null) {
            condition.append(" AND enabled = :enabled")
            parameters.enabled = enabled ? "Y" : "N"
        }
        
        def result = ec.entity.find("moqui.security.UserAccount")
            .condition(condition.toString(), parameters)
            .limit(50)
            .list()
        
        return [
            content: [[type: "text", text: "Found ${result.size()} users: ${result.collect { [username: it.username, email: it.emailAddress, enabled: it.enabled] }}"]],
            isError: false
        ]
    }
    
    private Map<String, Object> callPartyFind(Map arguments, ExecutionContextImpl ec) {
        String partyType = arguments.partyType as String
        String partyName = arguments.partyName as String
        String status = arguments.status as String
        
        def condition = new StringBuilder("1=1")
        def parameters = [:]
        
        if (partyType) {
            condition.append(" AND party_type_id = :partyType")
            parameters.partyType = partyType
        }
        if (partyName) {
            condition.append(" AND (party_name ILIKE :partyName OR party_name ILIKE :partyName)")
            parameters.partyName = "%${partyName}%"
        }
        if (status) {
            condition.append(" AND status_id = :status")
            parameters.status = status
        }
        
        def result = ec.entity.find("mantle.party.PartyAndName")
            .condition(condition.toString(), parameters)
            .limit(50)
            .list()
        
        return [
            content: [[type: "text", text: "Found ${result.size()} parties: ${result.collect { [partyId: it.partyId, type: it.partyTypeId, name: it.partyName, status: it.statusId] }}"]],
            isError: false
        ]
    }
    
    private Map<String, Object> callOrderFind(Map arguments, ExecutionContextImpl ec) {
        String orderId = arguments.orderId as String
        String customerId = arguments.customerId as String
        String status = arguments.status as String
        String fromDate = arguments.fromDate as String
        String thruDate = arguments.thruDate as String
        
        def condition = new StringBuilder("1=1")
        def parameters = [:]
        
        if (orderId) {
            condition.append(" AND order_id = :orderId")
            parameters.orderId = orderId
        }
        if (customerId) {
            condition.append(" AND customer_party_id = :customerId")
            parameters.customerId = customerId
        }
        if (status) {
            condition.append(" AND status_id = :status")
            parameters.status = status
        }
        if (fromDate) {
            condition.append(" AND order_date >= :fromDate")
            parameters.fromDate = fromDate
        }
        if (thruDate) {
            condition.append(" AND order_date <= :thruDate")
            parameters.thruDate = thruDate
        }
        
        def result = ec.entity.find("mantle.order.OrderHeader")
            .condition(condition.toString(), parameters)
            .limit(50)
            .list()
        
        return [
            content: [[type: "text", text: "Found ${result.size()} orders: ${result.collect { [orderId: it.orderId, customer: it.customerPartyId, status: it.statusId, date: it.orderDate, total: it.grandTotal] }}"]],
            isError: false
        ]
    }
    
    private Map<String, Object> callProductFind(Map arguments, ExecutionContextImpl ec) {
        String productId = arguments.productId as String
        String productName = arguments.productName as String
        String productType = arguments.productType as String
        String category = arguments.category as String
        
        def condition = new StringBuilder("1=1")
        def parameters = [:]
        
        if (productId) {
            condition.append(" AND product_id = :productId")
            parameters.productId = productId
        }
        if (productName) {
            condition.append(" AND (product_name ILIKE :productName OR internal_name ILIKE :productName)")
            parameters.productName = "%${productName}%"
        }
        if (productType) {
            condition.append(" AND product_type_id = :productType")
            parameters.productType = productType
        }
        if (category) {
            condition.append(" AND primary_product_category_id = :category")
            parameters.category = category
        }
        
        def result = ec.entity.find("mantle.product.Product")
            .condition(condition.toString(), parameters)
            .limit(50)
            .list()
        
        return [
            content: [[type: "text", text: "Found ${result.size()} products: ${result.collect { [productId: it.productId, name: it.productName, type: it.productTypeId, category: it.primaryProductCategoryId] }}"]],
            isError: false
        ]
    }
    
    private Map<String, Object> callInventoryCheck(Map arguments, ExecutionContextImpl ec) {
        String productId = arguments.productId as String
        String facilityId = arguments.facilityId as String
        String locationId = arguments.locationId as String
        
        if (!productId) {
            return [
                content: [[type: "text", text: "Error: productId is required"]],
                isError: true
            ]
        }
        
        def condition = new StringBuilder("product_id = :productId")
        def parameters = [productId: productId]
        
        if (facilityId) {
            condition.append(" AND facility_id = :facilityId")
            parameters.facilityId = facilityId
        }
        if (locationId) {
            condition.append(" AND location_id = :locationId")
            parameters.locationId = locationId
        }
        
        def result = ec.entity.find("mantle.product.inventory.InventoryItem")
            .condition(condition.toString(), parameters)
            .list()
        
        def totalAvailable = result.sum { it.availableToPromiseTotal ?: 0 }
        def totalOnHand = result.sum { it.quantityOnHandTotal ?: 0 }
        
        return [
            content: [[type: "text", text: "Inventory for ${productId}: Available: ${totalAvailable}, On Hand: ${totalOnHand}, Facilities: ${result.collect { [facility: it.facilityId, location: it.locationId, available: it.availableToPromiseTotal, onHand: it.quantityOnHandTotal] }}"]],
            isError: false
        ]
    }
    
    private Map<String, Object> callSystemStatus(Map arguments, ExecutionContextImpl ec) {
        Boolean includeMetrics = arguments.includeMetrics as Boolean ?: false
        Boolean includeCache = arguments.includeCache as Boolean ?: false
        
        def status = [
            serverTime: new Date(),
            frameworkVersion: "3.1.0-rc2",
            userCount: ec.entity.find("moqui.security.UserAccount").count(),
            partyCount: ec.entity.find("mantle.party.Party").count(),
            productCount: ec.entity.find("mantle.product.Product").count(),
            orderCount: ec.entity.find("mantle.order.OrderHeader").count()
        ]
        
        if (includeMetrics) {
            status.memory = [
                total: Runtime.getRuntime().totalMemory(),
                free: Runtime.getRuntime().freeMemory(),
                max: Runtime.getRuntime().maxMemory()
            ]
        }
        
        if (includeCache) {
            def cacheFacade = ec.getCache()
            status.cache = [
                cacheNames: cacheFacade.getCacheNames(),
                // Note: More detailed cache stats would require cache-specific API calls
            ]
        }
        
        return [
            content: [[type: "text", text: "System Status: ${status}"]],
            isError: false
        ]
    }
    
    private Map<String, Object> listResources(Map params, ExecutionContextImpl ec) {
        // List available entities as resources
        def resources = []
        
        // Get all entity names
        def entityNames = ec.entity.getEntityNames()
        for (String entityName : entityNames) {
            resources << [
                uri: "entity://${entityName}",
                name: entityName,
                description: "Moqui Entity: ${entityName}",
                mimeType: "application/json"
            ]
        }
        
        return [resources: resources]
    }
    
    private Map<String, Object> readResource(Map params, ExecutionContextImpl ec) {
        String uri = params.uri as String
        
        if (uri.startsWith("entity://")) {
            String entityName = uri.substring(9) // Remove "entity://" prefix
            
            try {
                // Get entity definition
                def entityDef = ec.entity.getEntityDefinition(entityName)
                if (!entityDef) {
                    throw new IllegalArgumentException("Entity not found: ${entityName}")
                }
                
                // Get basic entity info
                def entityInfo = [
                    name: entityName,
                    tableName: entityDef.tableName,
                    fields: entityDef.allFieldInfo.collect { [name: it.name, type: it.type] }
                ]
                
                return [
                    contents: [[
                        uri: uri,
                        mimeType: "application/json",
                        text: groovy.json.JsonOutput.toJson(entityInfo)
                    ]]
                ]
            } catch (Exception e) {
                throw new IllegalArgumentException("Error reading entity ${entityName}: " + e.message)
            }
        } else {
            throw new IllegalArgumentException("Unsupported resource URI: ${uri}")
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
}