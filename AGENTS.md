# Moqui MCP v2.0 Server Guide

This guide explains how to interact with Moqui MCP v2.0 server for AI-Moqui integration using Model Context Protocol (MCP) and Moqui's unified screen-based implementation.

## Repository Status

**moqui-mcp-2** is a **standalone component with its own git repository**. It uses a unified screen-based approach that handles both JSON-RPC and Server-Sent Events (SSE) for maximum MCP client compatibility.

- **Repository**: Independent git repository
- **Integration**: Included as plain directory in moqui-opencode
- **Version**: v2.0.0 (unified screen implementation)
- **Approach**: Moqui screen-based with dual JSON-RPC/SSE support
- **Status**: ✅ **PRODUCTION READY** - All tests passing

## Architecture Overview

```
moqui-mcp-2/
├── AGENTS.md                    # This file - MCP server guide
├── component.xml               # Component configuration
├── screen/
│   └── webroot/
│       └── mcp.xml            # Unified MCP screen (JSON-RPC + SSE)
├── service/
│   ├── McpServices.xml         # MCP services implementation
│   └── mcp.rest.xml          # Basic REST endpoints
└── data/
    └── McpSecuritySeedData.xml  # Security permissions
```

## Implementation Status

### **✅ COMPLETED FEATURES**
- **Unified Screen Architecture**: Single endpoint handles both JSON-RPC and SSE
- **Content-Type Negotiation**: Correctly prioritizes `application/json` over `text/event-stream`
- **Session Management**: MCP session IDs generated and validated
- **Security Integration**: Full Moqui security framework integration
- **Method Mapping**: All MCP methods properly mapped to Moqui services
- **Error Handling**: Comprehensive error responses for both formats
- **Protocol Headers**: MCP protocol version headers set correctly

### **✅ ENDPOINT CONFIGURATION**
- **Primary Endpoint**: `http://localhost:8080/mcp/rpc`
- **JSON-RPC Support**: `Accept: application/json` → JSON responses
- **SSE Support**: `Accept: text/event-stream` → Server-Sent Events
- **Authentication**: Basic auth with `mcp-user:moqui` credentials
- **Session Headers**: `Mcp-Session-Id` for session persistence

## MCP Implementation Strategy

### **Unified Screen Design**
- ✅ **Single Endpoint**: `/mcp/rpc` handles both JSON-RPC and SSE
- ✅ **Content-Type Negotiation**: Automatic response format selection
- ✅ **Standard Services**: MCP methods as regular Moqui services
- ✅ **Native Authentication**: Leverages Moqui's user authentication
- ✅ **Direct Integration**: No custom layers or abstractions
- ✅ **Audit Logging**: Uses Moqui's ArtifactHit framework

### **MCP Method Mapping**
```
MCP Method              → Moqui Service
initialize              → McpServices.mcp#Initialize
tools/list              → McpServices.mcp#ToolsList
tools/call              → McpServices.mcp#ToolsCall
resources/list           → McpServices.mcp#ResourcesList
resources/read           → McpServices.mcp#ResourcesRead
ping                    → McpServices.mcp#Ping
```

### **Response Format Handling**
```
Accept Header                    → Response Format
application/json               → JSON-RPC 2.0 response
text/event-stream              → Server-Sent Events
application/json, text/event-stream → JSON-RPC 2.0 (prioritized)
```

## MCP Endpoint

### **Unified Screen Endpoint**
```
POST /mcp/rpc
- Single screen handles both JSON-RPC and SSE
- Content-Type negotiation determines response format
- Built-in authentication and audit logging
- MCP protocol version headers included
```

### **Request Flow**
1. **Authentication**: Basic auth validates user credentials
2. **Session Management**: Initialize generates session ID, subsequent requests require it
3. **Content-Type Negotiation**: Accept header determines response format
4. **Method Routing**: MCP methods mapped to Moqui services
5. **Response Generation**: JSON-RPC or SSE based on client preference

### **JSON-RPC Request Format**
```json
{
  "jsonrpc": "2.0",
  "id": "unique_request_id",
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-06-18",
    "capabilities": {
      "roots": {},
      "sampling": {}
    },
    "clientInfo": {
      "name": "AI Client",
      "version": "1.0.0"
    }
  }
}
```

### **SSE Request Format**
```bash
curl -X POST http://localhost:8080/mcp/rpc \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "Authorization: Basic bWNwLXVzZXI6bW9xdWk=" \
  -H "Mcp-Session-Id: <session-id>" \
  -d '{"jsonrpc":"2.0","id":"ping-1","method":"ping","params":{}}'
```

### **SSE Response Format**
```
event: response
data: {"jsonrpc":"2.0","id":"ping-1","result":{"result":{"timestamp":"2025-11-14T23:16:14+0000","status":"healthy","version":"2.0.0"}}}
```

## MCP Client Integration

### **Python Client Example**
```python
import requests
import json
import uuid

class MoquiMCPClient:
    def __init__(self, base_url, username, password):
        self.base_url = base_url
        self.request_id = 0
        self.authenticate(username, password)
    
    def _make_request(self, method, params=None):
        self.request_id += 1
        payload = {
            "jsonrpc": "2.0",
            "id": f"req_{self.request_id}",
            "method": method,
            "params": params or {}
        }
        
        response = requests.post(f"{self.base_url}/mcp/rpc", json=payload)
        return response.json()
    
    def initialize(self):
        return self._make_request("org.moqui.mcp.McpServices.mcp#Initialize", {
            "protocolVersion": "2025-06-18",
            "capabilities": {
                "roots": {},
                "sampling": {}
            },
            "clientInfo": {
                "name": "Python MCP Client",
                "version": "1.0.0"
            }
        })
    
    def list_tools(self):
        return self._make_request("org.moqui.mcp.McpServices.mcp#ToolsList")
    
    def call_tool(self, tool_name, arguments):
        return self._make_request("org.moqui.mcp.McpServices.mcp#ToolsCall", {
            "name": tool_name,
            "arguments": arguments
        })
    
    def list_resources(self):
        return self._make_request("org.moqui.mcp.McpServices.mcp#ResourcesList")
    
    def read_resource(self, uri):
        return self._make_request("org.moqui.mcp.McpServices.mcp#ResourcesRead", {
            "uri": uri
        })
    
    def ping(self):
        return self._make_request("org.moqui.mcp.McpServices.mcp#Ping")

# Usage
client = MoquiMCPClient("http://localhost:8080", "admin", "moqui")
init_result = client.initialize()
tools = client.list_tools()
result = client.call_tool("org.moqui.example.Services.create#Example", {
    "exampleName": "Test Example"
})
```

### **JavaScript Client Example**
```javascript
class MoquiMCPClient {
    constructor(baseUrl, username, password) {
        this.baseUrl = baseUrl;
        this.requestId = 0;
        this.authenticate(username, password);
    }
    
    async makeRequest(method, params = {}) {
        this.requestId++;
        const payload = {
            jsonrpc: "2.0",
            id: `req_${this.requestId}`,
            method,
            params
        };
        
        const response = await fetch(`${this.baseUrl}/mcp/rpc`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        
        return response.json();
    }
    
    async initialize() {
        return this.makeRequest('org.moqui.mcp.McpServices.mcp#Initialize', {
            protocolVersion: '2025-06-18',
            capabilities: {
                roots: {},
                sampling: {}
            },
            clientInfo: {
                name: 'JavaScript MCP Client',
                version: '1.0.0'
            }
        });
    }
    
    async listTools() {
        return this.makeRequest('org.moqui.mcp.McpServices.mcp#ToolsList');
    }
    
    async callTool(toolName, arguments) {
        return this.makeRequest('org.moqui.mcp.McpServices.mcp#ToolsCall', {
            name: toolName,
            arguments
        });
    }
    
    async listResources() {
        return this.makeRequest('org.moqui.mcp.McpServices.mcp#ResourcesList');
    }
    
    async readResource(uri) {
        return this.makeRequest('org.moqui.mcp.McpServices.mcp#ResourcesRead', {
            uri
        });
    }
    
    async ping() {
        return this.makeRequest('org.moqui.mcp.McpServices.mcp#Ping');
    }
}

// Usage
const client = new MoquiMCPClient('http://localhost:8080', 'admin', 'moqui');
await client.initialize();
const tools = await client.listTools();
const result = await client.callTool('org.moqui.example.Services.create#Example', {
    exampleName: 'Test Example'
});
```

### **cURL Examples**

#### **Initialize MCP Session**
```bash
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "jsonrpc": "2.0",
    "id": "init_001",
    "method": "org.moqui.mcp.McpServices.mcp#Initialize",
    "params": {
      "protocolVersion": "2025-06-18",
      "capabilities": {
        "roots": {},
        "sampling": {}
      },
      "clientInfo": {
        "name": "cURL Client",
        "version": "1.0.0"
      }
    }
  }' \
  http://localhost:8080/mcp/rpc
```

#### **List Available Tools**
```bash
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "jsonrpc": "2.0",
    "id": "tools_001",
    "method": "org.moqui.mcp.McpServices.mcp#ToolsList",
    "params": {}
  }' \
  http://localhost:8080/mcp/rpc
```

#### **Execute Tool**
```bash
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "jsonrpc": "2.0",
    "id": "call_001",
    "method": "org.moqui.mcp.McpServices.mcp#ToolsCall",
    "params": {
      "name": "org.moqui.example.Services.create#Example",
      "arguments": {
        "exampleName": "JSON-RPC Test",
        "statusId": "EXST_ACTIVE"
      }
    }
  }' \
  http://localhost:8080/mcp/rpc
```

#### **Read Entity Resource**
```bash
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "jsonrpc": "2.0",
    "id": "resource_001",
    "method": "org.moqui.mcp.McpServices.mcp#ResourcesRead",
    "params": {
      "uri": "entity://Example"
    }
  }' \
  http://localhost:8080/mcp/rpc
```

#### **Health Check**
```bash
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "jsonrpc": "2.0",
    "id": "ping_001",
    "method": "org.moqui.mcp.McpServices.mcp#Ping",
    "params": {}
  }' \
  http://localhost:8080/mcp/rpc
```

## Security and Permissions

### **Authentication**
- Uses Moqui's built-in user authentication
- Pass credentials via standard JSON-RPC `authUsername` and `authPassword` parameters
- Or use existing session via Moqui's web session

### **Authorization**
- All MCP services require `authenticate="true"`
- Tool access controlled by Moqui's service permissions
- Entity access controlled by Moqui's entity permissions
- Audit logging via Moqui's ArtifactHit framework

### **Permission Setup**
```xml
<!-- MCP User Group -->
<moqui.security.UserGroup userGroupId="McpUser" description="MCP Server Users"/>

<!-- MCP Artifact Groups -->
<moqui.security.ArtifactGroup artifactGroupId="McpServices" description="MCP JSON-RPC Services"/>
<moqui.security.ArtifactGroup artifactGroupId="McpRestPaths" description="MCP REST API Paths"/>
<moqui.security.ArtifactGroup artifactGroupId="McpScreenTransitions" description="MCP Screen Transitions"/>

<!-- MCP Service Permissions -->
<moqui.security.ArtifactAuthz userGroupId="McpUser" 
    artifactGroupId="McpServices" 
    authzTypeEnumId="AUTHZT_ALLOW" 
    authzActionEnumId="AUTHZA_ALL"/>
<moqui.security.ArtifactAuthz userGroupId="McpUser" 
    artifactGroupId="McpRestPaths" 
    authzTypeEnumId="AUTHZT_ALLOW" 
    authzActionEnumId="AUTHZA_ALL"/>
<moqui.security.ArtifactAuthz userGroupId="McpUser" 
    artifactGroupId="McpScreenTransitions" 
    authzTypeEnumId="AUTHZT_ALLOW" 
    authzActionEnumId="AUTHZA_ALL"/>
```

## Development and Testing

### **Local Development**
1. Start Moqui with moqui-mcp-2 component
2. Test with JSON-RPC client examples above
3. Check Moqui logs for debugging
4. Monitor ArtifactHit records for audit trail

### **Service Discovery**
- All Moqui services automatically available as MCP tools
- Filtered by user permissions
- Service parameters converted to JSON Schema
- Service descriptions used for tool descriptions

### **Entity Access**
- All Moqui entities automatically available as MCP resources
- Format: `entity://EntityName`
- Filtered by entity VIEW permissions
- Limited to 100 records per request

## Error Handling

### **JSON-RPC Error Responses**
```json
{
  "jsonrpc": "2.0",
  "id": "req_001",
  "error": {
    "code": -32601,
    "message": "Method not found"
  }
}
```

### **Common Error Codes**
- `-32600`: Invalid Request (malformed JSON-RPC)
- `-32601`: Method not found
- `-32602`: Invalid params
- `-32603`: Internal error (service execution failed)

### **Service-Level Errors**
- Permission denied throws Exception with message
- Invalid service name throws Exception
- Entity access violations throw Exception
- All errors logged to Moqui error system

## Monitoring and Debugging

### **Audit Trail**
- All MCP calls logged to `moqui.server.ArtifactHit`
- Track user, service, parameters, execution time
- Monitor success/failure rates
- Debug via Moqui's built-in monitoring tools

### **Performance Metrics**
- Service execution time tracked
- Response size monitored
- Error rates captured
- User activity patterns available

---

## Testing Results

### **✅ VERIFIED FUNCTIONALITY**
- **JSON-RPC Initialize**: Session creation and protocol negotiation
- **JSON-RPC Ping**: Health check with proper response format
- **JSON-RPC Tools List**: Service discovery working correctly
- **SSE Responses**: Server-sent events with proper event formatting
- **Session Management**: Session ID generation and validation
- **Content-Type Negotiation**: Correct prioritization of JSON over SSE
- **Security Integration**: All three authorization layers working
- **Error Handling**: Proper error responses for both formats

### **✅ CLIENT COMPATIBILITY**
- **opencode Client**: Ready for production use
- **Standard MCP Clients**: Full protocol compliance
- **Custom Integrations**: Easy integration patterns documented

## Key Advantages

### **Unified Screen Benefits**
1. **Single Endpoint**: One URL handles all MCP protocol variations
2. **Content-Type Negotiation**: Automatic response format selection
3. **Client Compatibility**: Works with both JSON-RPC and SSE clients
4. **Simplicity**: No custom webapps or complex routing needed
5. **Reliability**: Uses battle-tested Moqui screen framework
6. **Security**: Leverages Moqui's mature security system
7. **Audit**: Built-in logging and monitoring
8. **Maintenance**: Minimal custom code to maintain

### **Clean Architecture**
- Single screen handles all MCP protocol logic
- No custom authentication layers
- Standard Moqui session management
- Direct service-to-tool mapping
- Standard Moqui patterns throughout
- Full MCP protocol compliance

**This implementation demonstrates the power of Moqui's screen framework for unified MCP protocol handling.**