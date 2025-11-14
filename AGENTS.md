# Moqui MCP v2 Server Agent Guide

This guide explains how to interact with the Moqui MCP v2 server component for AI-Moqui integration using the Model Context Protocol (MCP).

## Architecture Overview

```
moqui-mcp-2/
├── AGENTS.md                    # This file - MCP server agent guide
├── component.xml               # Component configuration
├── entity/                     # MCP entity definitions
│   └── McpCoreEntities.xml     # Core MCP entities
├── service/                    # MCP service implementations
│   ├── McpDiscoveryServices.xml # Tool discovery services
│   ├── McpExecutionServices.xml # Tool execution services
│   ├── McpSecurityServices.xml  # Session/security services
│   └── mcp.rest.xml            # REST API endpoints
├── screen/                     # Web interface screens
│   └── webapp.xml              # MCP web interface
└── template/                   # UI templates
    └── McpInfo.html.ftl        # MCP info page
```

## MCP Server Capabilities

### Core Features
- **Session Management**: Secure session creation, validation, and termination
- **Tool Discovery**: Dynamic discovery of available Moqui services and entities
- **Tool Execution**: Secure execution of services with proper authorization
- **Entity Operations**: Direct entity query capabilities with permission checks
- **Health Monitoring**: Server health and status monitoring

### Security Model
- **Session-based Authentication**: Uses Moqui's built-in user authentication
- **Context Tokens**: Secure token-based session validation
- **Permission Validation**: All operations checked against Moqui security framework
- **Audit Logging**: Complete audit trail of all MCP operations

## REST API Endpoints

### Session Management
```
POST /mcp-2/session
- Create new MCP session
- Parameters: username, password, clientInfo, ipAddress, userAgent
- Returns: sessionId, contextToken, expiration

POST /mcp-2/session/{visitId}/validate  
- Validate existing session
- Parameters: visitId (from path), contextToken
- Returns: session validity, user info

POST /mcp-2/session/{visitId}/terminate
- Terminate active session
- Parameters: visitId (from path), contextToken
- Returns: termination confirmation
```

### Tool Discovery
```
POST /mcp-2/tools/discover
- Discover available MCP tools
- Parameters: userAccountId, visitId, servicePattern, packageName, verb, noun
- Returns: List of available tools with metadata

POST /mcp-2/tools/validate
- Validate access to specific tools
- Parameters: userAccountId, serviceNames, visitId
- Returns: Access validation results
```

### Tool Execution
```
POST /mcp-2/tools/execute
- Execute service as MCP tool
- Parameters: sessionId, contextToken, serviceName, parameters, toolCallId
- Returns: Service execution results

POST /mcp-2/tools/entity/query
- Execute entity query as MCP tool
- Parameters: sessionId, contextToken, serviceName, parameters, toolCallId
- Returns: Entity query results
```

### Health Check
```
GET /mcp-2/health
- Server health status
- Returns: Server status, version, available endpoints
```

## Agent Integration Patterns

### 1. Session Initialization
```bash
# Create session
curl -X POST http://localhost:8080/mcp-2/session \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "admin",
    "clientInfo": "AI-Agent-v1.0"
  }'

# Response: { "sessionId": "12345", "contextToken": "abc123", "expires": "2025-01-01T00:00:00Z" }
```

### 2. Tool Discovery Workflow
```bash
# Discover available tools
curl -X POST http://localhost:8080/mcp-2/tools/discover \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer abc123" \
  -d '{
    "userAccountId": "ADMIN",
    "visitId": "12345",
    "servicePattern": "org.moqui.*"
  }'

# Response: { "tools": [ { "name": "create#Order", "description": "...", "parameters": [...] } ] }
```

### 3. Tool Execution Pattern
```bash
# Execute a service
curl -X POST http://localhost:8080/mcp-2/tools/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer abc123" \
  -d '{
    "sessionId": "12345",
    "contextToken": "abc123",
    "serviceName": "org.moqui.example.Services.create#Example",
    "parameters": { "field1": "value1", "field2": "value2" },
    "toolCallId": "call_123"
  }'

# Response: { "result": { "exampleId": "EX123" }, "success": true }
```

## Security Considerations

### Authentication Requirements
- All MCP operations require valid session authentication
- Context tokens must be included in all requests after session creation
- Sessions expire based on configured timeout settings

### Permission Validation
- Tool access validated against Moqui user permissions
- Entity operations respect Moqui entity access controls
- Service execution requires appropriate service permissions

### Audit and Logging
- All MCP operations logged to Moqui audit system
- Session creation, tool discovery, and execution tracked
- Failed authentication attempts monitored

## Error Handling

### Common Error Responses
```json
{
  "error": "INVALID_SESSION",
  "message": "Session expired or invalid",
  "code": 401
}

{
  "error": "PERMISSION_DENIED", 
  "message": "User lacks permission for tool",
  "code": 403
}

{
  "error": "TOOL_NOT_FOUND",
  "message": "Requested tool not available",
  "code": 404
}
```

### Error Recovery Strategies
- **Session Expiration**: Re-authenticate and create new session
- **Permission Issues**: Request additional permissions or use alternative tools
- **Tool Not Found**: Re-discover available tools and update tool registry

## Performance Optimization

### Caching Strategies
- Tool discovery results cached for session duration
- Permission validation results cached per user
- Entity query results cached based on Moqui cache settings

### Connection Management
- Use persistent HTTP connections where possible
- Implement session pooling for high-frequency operations
- Monitor session lifecycle and cleanup expired sessions

## Integration Examples

### Python Client Example
```python
import requests
import json

class MoquiMCPClient:
    def __init__(self, base_url, username, password):
        self.base_url = base_url
        self.session_id = None
        self.context_token = None
        self.authenticate(username, password)
    
    def authenticate(self, username, password):
        response = requests.post(f"{self.base_url}/mcp-2/session", json={
            "username": username,
            "password": password
        })
        data = response.json()
        self.session_id = data["sessionId"]
        self.context_token = data["contextToken"]
    
    def discover_tools(self, pattern="*"):
        response = requests.post(f"{self.base_url}/mcp-2/tools/discover", 
            headers={"Authorization": f"Bearer {self.context_token}"},
            json={"servicePattern": pattern}
        )
        return response.json()
    
    def execute_tool(self, service_name, parameters):
        response = requests.post(f"{self.base_url}/mcp-2/tools/execute",
            headers={"Authorization": f"Bearer {self.context_token}"},
            json={
                "sessionId": self.session_id,
                "contextToken": self.context_token,
                "serviceName": service_name,
                "parameters": parameters
            }
        )
        return response.json()
```

### JavaScript Client Example
```javascript
class MoquiMCPClient {
    constructor(baseUrl, username, password) {
        this.baseUrl = baseUrl;
        this.authenticate(username, password);
    }
    
    async authenticate(username, password) {
        const response = await fetch(`${this.baseUrl}/mcp-2/session`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, password })
        });
        const data = await response.json();
        this.sessionId = data.sessionId;
        this.contextToken = data.contextToken;
    }
    
    async discoverTools(pattern = '*') {
        const response = await fetch(`${this.baseUrl}/mcp-2/tools/discover`, {
            method: 'POST',
            headers: { 
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${this.contextToken}`
            },
            body: JSON.stringify({ servicePattern: pattern })
        });
        return response.json();
    }
    
    async executeTool(serviceName, parameters) {
        const response = await fetch(`${this.baseUrl}/mcp-2/tools/execute`, {
            method: 'POST',
            headers: { 
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${this.contextToken}`
            },
            body: JSON.stringify({
                sessionId: this.sessionId,
                contextToken: this.contextToken,
                serviceName,
                parameters
            })
        });
        return response.json();
    }
}
```

## Monitoring and Debugging

### Health Monitoring
- Monitor `/mcp-2/health` endpoint for server status
- Track session creation and termination rates
- Monitor tool execution success/failure rates

### Debug Information
- Check Moqui logs for MCP-related errors
- Use Moqui's built-in monitoring tools
- Monitor REST API access patterns

### Performance Metrics
- Session creation time
- Tool discovery response time  
- Tool execution duration
- Concurrent session limits

## Configuration

### Component Configuration
- Session timeout settings in `component.xml`
- Security permission mappings
- Cache configuration for performance

### Runtime Configuration
- Database connection settings
- REST API endpoint configuration
- Security context configuration

---

**This guide focuses on MCP server interaction patterns and agent integration.**
For Moqui framework integration details, see the main project AGENTS.md files.
For component-specific implementation details, refer to individual service files.