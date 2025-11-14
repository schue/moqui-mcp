# Moqui MCP v2.0 Server Guide

This guide explains how to interact with Moqui MCP v2.0 server for AI-Moqui integration using Model Context Protocol (MCP) and Moqui's built-in JSON-RPC support.

## Repository Status

**moqui-mcp-2** is a **standalone component with its own git repository**. It uses Moqui's native JSON-RPC framework for maximum compatibility and minimal complexity.

- **Repository**: Independent git repository
- **Integration**: Included as plain directory in moqui-opencode
- **Version**: v2.0.0 (clean MCP implementation)
- **Approach**: Moqui-centric using built-in JSON-RPC support

## Architecture Overview

```
moqui-mcp-2/
├── AGENTS.md                    # This file - MCP server guide
├── component.xml               # Component configuration
├── service/
│   └── McpServices.xml         # MCP services with allow-remote="true"
└── data/
    └── McpSecuritySeedData.xml  # Security permissions
```

## MCP Implementation Strategy

### **Moqui-Centric Design**
- ✅ **Built-in JSON-RPC**: Uses Moqui's `/rpc/json` endpoint
- ✅ **Standard Services**: MCP methods as regular Moqui services
- ✅ **Native Authentication**: Leverages Moqui's user authentication
- ✅ **Direct Integration**: No custom layers or abstractions
- ✅ **Audit Logging**: Uses Moqui's ArtifactHit framework

### **MCP Method Mapping**
```
MCP Method              → Moqui Service
initialize              → org.moqui.mcp.McpServices.mcp#Initialize
tools/list              → org.moqui.mcp.McpServices.mcp#ToolsList
tools/call              → org.moqui.mcp.McpServices.mcp#ToolsCall
resources/list           → org.moqui.mcp.McpServices.mcp#ResourcesList
resources/read           → org.moqui.mcp.McpServices.mcp#ResourcesRead
ping                    → org.moqui.mcp.McpServices.mcp#Ping
```

## JSON-RPC Endpoint

### **Moqui Built-in Endpoint**
```
POST /rpc/json
- Moqui's native JSON-RPC 2.0 endpoint
- Handles all services with allow-remote="true"
- Built-in authentication and audit logging
```

### **JSON-RPC Request Format**
```json
{
  "jsonrpc": "2.0",
  "id": "unique_request_id",
  "method": "org.moqui.mcp.McpServices.mcp#Initialize",
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
        
        response = requests.post(f"{self.base_url}/rpc/json", json=payload)
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
        
        const response = await fetch(`${this.baseUrl}/rpc/json`, {
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
  http://localhost:8080/rpc/json
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
  http://localhost:8080/rpc/json
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
  http://localhost:8080/rpc/json
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
  http://localhost:8080/rpc/json
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
  http://localhost:8080/rpc/json
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

<!-- MCP Service Permissions -->
<moqui.security.ArtifactAuthz userGroupId="McpUser" 
    artifactGroupId="McpServices" 
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

## Key Advantages

### **Moqui-Centric Benefits**
1. **Simplicity**: No custom JSON-RPC handling needed
2. **Reliability**: Uses battle-tested Moqui framework
3. **Security**: Leverages Moqui's mature security system
4. **Audit**: Built-in logging and monitoring
5. **Compatibility**: Standard JSON-RPC 2.0 compliance
6. **Maintenance**: Minimal custom code to maintain

### **Clean Architecture**
- No custom webapps or screens needed
- No custom authentication layers
- No custom session management
- Direct service-to-tool mapping
- Standard Moqui patterns throughout

**This implementation demonstrates the power of Moqui's built-in JSON-RPC support for MCP integration.**