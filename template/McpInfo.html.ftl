<!DOCTYPE html>
<html>
<head>
    <title>Moqui MCP Server v2</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; }
        .header { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
        .endpoint { background: #f8f9fa; padding: 10px; margin: 5px 0; border-radius: 5px; }
        .method { color: #28a745; font-weight: bold; }
        .path { font-family: monospace; background: #e9ecef; padding: 2px 5px; }
    </style>
</head>
<body>
    <h1 class="header">Moqui MCP Server v2 (MVP)</h1>
    
    <h2>Server Information</h2>
    <p><strong>Version:</strong> 2.0.0-MVP</p>
    <p><strong>Status:</strong> Active</p>
    <p><strong>Description:</strong> Simplified MCP server using Moqui's native service engine</p>
    
    <h2>Available Endpoints</h2>
    
    <div class="endpoint">
        <span class="method">POST</span> <span class="path">/session</span><br>
        <strong>Description:</strong> Create new MCP session<br>
        <strong>Required:</strong> username, password<br>
        <strong>Optional:</strong> clientInfo, ipAddress, userAgent
    </div>
    
    <div class="endpoint">
        <span class="method">POST</span> <span class="path">/session/{sessionId}/validate</span><br>
        <strong>Description:</strong> Validate MCP session<br>
        <strong>Required:</strong> contextToken
    </div>
    
    <div class="endpoint">
        <span class="method">POST</span> <span class="path">/session/{sessionId}/terminate</span><br>
        <strong>Description:</strong> Terminate MCP session<br>
        <strong>Required:</strong> contextToken
    </div>
    
    <div class="endpoint">
        <span class="method">POST</span> <span class="path">/tools/discover</span><br>
        <strong>Description:</strong> Discover available MCP tools<br>
        <strong>Required:</strong> userAccountId<br>
        <strong>Optional:</strong> sessionId, servicePattern, packageName, verb, noun, includeParameters
    </div>
    
    <div class="endpoint">
        <span class="method">POST</span> <span class="path">/tools/validate</span><br>
        <strong>Description:</strong> Validate access to specific MCP tools<br>
        <strong>Required:</strong> userAccountId, serviceNames<br>
        <strong>Optional:</strong> sessionId
    </div>
    
    <div class="endpoint">
        <span class="method">POST</span> <span class="path">/tools/execute</span><br>
        <strong>Description:</strong> Execute MCP tool (service)<br>
        <strong>Required:</strong> sessionId, serviceName, parameters<br>
        <strong>Optional:</strong> toolCallId
    </div>
    
    <div class="endpoint">
        <span class="method">POST</span> <span class="path">/tools/entity/query</span><br>
        <strong>Description:</strong> Execute entity query as MCP tool<br>
        <strong>Required:</strong> sessionId, entityName<br>
        <strong>Optional:</strong> queryType, conditions, orderBy, limit, offset, toolCallId
    </div>
    
    <div class="endpoint">
        <span class="method">GET</span> <span class="path">/health</span><br>
        <strong>Description:</strong> Server health check
    </div>
    
    <h2>Key Features</h2>
    <ul>
        <li><strong>Native Service Engine:</strong> Uses Moqui's service engine directly (no registry)</li>
        <li><strong>Dynamic Tool Discovery:</strong> Tools = services user has permission to execute</li>
        <li><strong>Unified Security:</strong> Single permission validation using UserGroupPermission</li>
        <li><strong>Audit Logging:</strong> Complete audit trail of all operations</li>
        <li><strong>Session Management:</strong> Secure session handling with expiration</li>
    </ul>
    
    <h2>Usage Example</h2>
    <pre>
# 1. Create session
curl -X POST http://localhost:8080/mcp-2/session \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin"}'

# 2. Discover tools
curl -X POST http://localhost:8080/mcp-2/tools/discover \
  -H "Content-Type: application/json" \
  -d '{"userAccountId": "ADMIN", "sessionId": "YOUR_SESSION_ID"}'

# 3. Execute tool
curl -X POST http://localhost:8080/mcp-2/tools/execute \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "YOUR_SESSION_ID",
    "serviceName": "org.moqui.entity.EntityServices.find#Entity",
    "parameters": {"entityName": "moqui.security.UserAccount"}
  }'
    </pre>
</body>
</html>