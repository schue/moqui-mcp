# Moqui MCP SDK Services Servlet

This document describes the MCP servlet implementation that combines the official Java MCP SDK with existing Moqui services for optimal architecture.

## Overview

The `McpSdkServicesServlet` provides the best of both worlds:

- **MCP SDK** for standards-compliant protocol handling and SSE transport
- **McpServices.xml** for proven business logic and Moqui integration
- **Delegation pattern** for clean separation of concerns
- **Service reuse** across different MCP transport implementations

## Architecture

### Component Interaction

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   MCP Client    │───▶│  MCP SDK Servlet │───▶│  McpServices.xml│
│                 │    │                  │    │                 │
│ - JSON-RPC 2.0  │    │ - Transport      │    │ - Business      │
│ - SSE Events    │    │ - Protocol       │    │   Logic         │
│ - Tool Calls    │    │ - Validation     │    │ - Security      │
└─────────────────┘    └──────────────────┘    └─────────────────┘
                              │
                              ▼
                       ┌─────────────────┐
                       │ Moqui Framework│
                       │                 │
                       │ - Entity Engine │
                       │ - Service Engine│
                       │ - Security      │
                       └─────────────────┘
```

### Service Delegation Pattern

| MCP Method | SDK Servlet | Moqui Service | Description |
|------------|-------------|---------------|-------------|
| `initialize` | `initialize` tool | `mcp#Initialize` | Session initialization |
| `ping` | `ping` tool | `mcp#Ping` | Health check |
| `tools/list` | `tools/list` tool | `mcp#ToolsList` | Discover available tools |
| `tools/call` | `tools/call` tool | `mcp#ToolsCall` | Execute tools/services |
| `resources/list` | `resources/list` tool | `mcp#ResourcesList` | Discover entities |
| `resources/read` | `resources/read` tool | `mcp#ResourcesRead` | Read entity data |

## Benefits

### 1. Separation of Concerns

- **SDK Servlet**: Protocol handling, transport, validation
- **McpServices.xml**: Business logic, security, data access
- **Clean interfaces**: Well-defined service contracts

### 2. Code Reuse

- **Single source of truth**: All MCP logic in McpServices.xml
- **Multiple transports**: Same services work with different servlets
- **Testable services**: Can test business logic independently

### 3. Standards Compliance

- **MCP SDK**: Guaranteed protocol compliance
- **JSON Schema**: Automatic validation
- **Error handling**: Standardized responses

### 4. Moqui Integration

- **Security**: Leverages Moqui authentication/authorization
- **Auditing**: Built-in artifact hit tracking
- **Transactions**: Proper transaction management
- **Error handling**: Moqui exception handling

## Configuration

### Dependencies

```gradle
dependencies {
    // MCP Java SDK for transport and protocol
    implementation 'io.modelcontextprotocol.sdk:mcp-sdk:1.0.0'
    
    // Jackson for JSON handling (required by MCP SDK)
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.15.2'
    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2'
    
    // Servlet API
    compileOnly 'javax.servlet:javax.servlet-api:4.0.1'
}
```

### Web.xml Configuration

```xml
<servlet>
    <servlet-name>McpSdkServicesServlet</servlet-name>
    <servlet-class>org.moqui.mcp.McpSdkServicesServlet</servlet-class>
    
    <init-param>
        <param-name>moqui-name</param-name>
        <param-value>moqui-mcp-2</param-value>
    </init-param>
    
    <async-supported>true</async-supported>
    <load-on-startup>1</load-on-startup>
</servlet>

<servlet-mapping>
    <servlet-name>McpSdkServicesServlet</servlet-name>
    <url-pattern>/mcp/*</url-pattern>
</servlet-mapping>
```

## Available Tools

### Core MCP Tools

| Tool | Service | Description |
|------|---------|-------------|
| `initialize` | `mcp#Initialize` | Initialize MCP session with capabilities negotiation |
| `ping` | `mcp#Ping` | Health check and server status |
| `tools/list` | `mcp#ToolsList` | List all available Moqui services as tools |
| `tools/call` | `mcp#ToolsCall` | Execute any Moqui service |
| `resources/list` | `mcp#ResourcesList` | List all accessible entities as resources |
| `resources/read` | `mcp#ResourcesRead` | Read entity data and metadata |
| `debug/component` | `debug#ComponentStatus` | Debug component status and configuration |

### Tool Examples

#### Initialize Session

```bash
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "init-1",
       "method": "tools/call",
       "params": {
         "name": "initialize",
         "arguments": {
           "protocolVersion": "2025-06-18",
           "clientInfo": {
             "name": "Test Client",
             "version": "1.0.0"
           }
         }
       }
     }'
```

#### List Available Tools

```bash
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "tools-1",
       "method": "tools/call",
       "params": {
         "name": "tools/list",
         "arguments": {}
       }
     }'
```

#### Execute Moqui Service

```bash
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "call-1",
       "method": "tools/call",
       "params": {
         "name": "tools/call",
         "arguments": {
           "name": "org.moqui.entity.EntityServices.find#List",
           "arguments": {
             "entityName": "moqui.security.UserAccount",
             "fields": ["username", "emailAddress"],
             "limit": 10
           }
         }
       }
     }'
```

## Available Resources

### Entity Resources

- **`entity://`** - List all entities
- **`entity://EntityName`** - Read specific entity data

#### Resource Examples

```bash
# List all entities
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "res-1",
       "method": "tools/call",
       "params": {
         "name": "resources/list",
         "arguments": {}
       }
     }'

# Read specific entity
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "res-2",
       "method": "tools/call",
       "params": {
         "name": "resources/read",
         "arguments": {
           "uri": "entity://moqui.security.UserAccount"
         }
       }
     }'
```

## Available Prompts

### Entity Query Prompt

Generate Moqui entity queries:

```bash
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "prompt-1",
       "method": "prompts/get",
       "params": {
         "name": "entity-query",
         "arguments": {
           "entity": "mantle.order.OrderHeader",
           "purpose": "Find recent orders",
           "fields": "orderId, orderDate, grandTotal"
         }
       }
     }'
```

### Service Execution Prompt

Generate Moqui service execution plans:

```bash
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "prompt-2",
       "method": "prompts/get",
       "params": {
         "name": "service-execution",
         "arguments": {
           "serviceName": "create#OrderItem",
           "description": "Create a new order item",
           "parameters": "{\"orderId\":\"string\",\"productId\":\"string\",\"quantity\":\"number\"}"
         }
       }
     }'
```

## Service Implementation Details

### McpServices.xml Features

The `McpServices.xml` provides comprehensive MCP functionality:

#### 1. Service Discovery (`mcp#ToolsList`)

```xml
<service verb="mcp" noun="ToolsList" authenticate="true" allow-remote="true">
    <description>Handle MCP tools/list request with direct Moqui service discovery</description>
    <actions>
        <script><![CDATA[
            // Get all service names from Moqui service engine
            def allServiceNames = ec.service.getKnownServiceNames()
            def availableTools = []
            
            // Convert services to MCP tools
            for (serviceName in allServiceNames) {
                if (ec.service.hasPermission(serviceName)) {
                    def serviceInfo = ec.service.getServiceInfo(serviceName)
                    // Convert to MCP tool format...
                }
            }
        ]]></script>
    </actions>
</service>
```

#### 2. Service Execution (`mcp#ToolsCall`)

```xml
<service verb="mcp" noun="ToolsCall" authenticate="true" allow-remote="true">
    <description>Handle MCP tools/call request with direct Moqui service execution</description>
    <actions>
        <script><![CDATA[
            // Validate service exists and user has permission
            if (!ec.service.hasPermission(name)) {
                throw new Exception("Permission denied for tool: ${name}")
            }
            
            // Create audit record
            def artifactHit = ec.entity.makeValue("moqui.server.ArtifactHit")
            // ... audit setup ...
            
            // Execute service
            def serviceResult = ec.service.sync().name(name).parameters(arguments).call()
            
            // Convert result to MCP format
            result = [content: content, isError: false]
        ]]></script>
    </actions>
</service>
```

#### 3. Entity Access (`mcp#ResourcesRead`)

```xml
<service verb="mcp" noun="ResourcesRead" authenticate="true" allow-remote="true">
    <description>Handle MCP resources/read request with Moqui entity queries</description>
    <actions>
        <script><![CDATA[
            // Parse entity URI and validate permissions
            if (!ec.user.hasPermission("entity:${entityName}", "VIEW")) {
                throw new Exception("Permission denied for entity: ${entityName}")
            }
            
            // Get entity definition and query data
            def entityDef = ec.entity.getEntityDefinition(entityName)
            def entityList = ec.entity.find(entityName).limit(100).list()
            
            // Build comprehensive response
            result = [contents: [[
                uri: uri,
                mimeType: "application/json",
                text: entityInfo + data
            ]]]
        ]]></script>
    </actions>
</service>
```

## Security Model

### Authentication Integration

- **Moqui Authentication**: Uses existing Moqui user accounts
- **Session Management**: Leverages Moqui web sessions
- **Permission Checking**: Validates service and entity permissions
- **Audit Trail**: Records all MCP operations in ArtifactHit

### Permission Model

| Operation | Permission Check | Description |
|------------|------------------|-------------|
| Service Execution | `ec.service.hasPermission(serviceName)` | Check service-level permissions |
| Entity Access | `ec.user.hasPermission("entity:EntityName", "VIEW")` | Check entity-level permissions |
| Resource Access | `ec.user.hasPermission("entity:EntityName", "VIEW")` | Check entity read permissions |

### Audit Logging

All MCP operations are automatically logged:

```groovy
def artifactHit = ec.entity.makeValue("moqui.server.ArtifactHit")
artifactHit.artifactType = "MCP"
artifactHit.artifactSubType = "Tool"  // or "Resource"
artifactHit.artifactName = serviceName
artifactHit.parameterString = new JsonBuilder(arguments).toString()
artifactHit.userId = ec.user.userId
artifactHit.create()
```

## Error Handling

### Service-Level Errors

The McpServices.xml provides comprehensive error handling:

```groovy
try {
    def serviceResult = ec.service.sync().name(name).parameters(arguments).call()
    result = [content: content, isError: false]
} catch (Exception e) {
    // Update audit record with error
    artifactHit.wasError = "Y"
    artifactHit.errorMessage = e.message
    artifactHit.update()
    
    result = [
        content: [[type: "text", text: "Error executing tool ${name}: ${e.message}"]],
        isError: true
    ]
}
```

### SDK-Level Errors

The MCP SDK handles protocol-level errors:

- **JSON-RPC validation** - Invalid requests
- **Schema validation** - Parameter validation
- **Transport errors** - Connection issues
- **Timeout handling** - Long-running operations

## Performance Considerations

### Service Caching

- **Service definitions** cached by Moqui service engine
- **Entity metadata** cached by entity engine
- **Permission checks** cached for performance
- **Audit records** batched for efficiency

### Connection Management

- **SSE connections** managed by MCP SDK
- **Async processing** for non-blocking operations
- **Connection limits** prevent resource exhaustion
- **Graceful shutdown** on servlet destroy

### Query Optimization

- **Entity queries** limited to 100 records by default
- **Field selection** reduces data transfer
- **Pagination support** for large result sets
- **Index utilization** through proper query construction

## Monitoring and Debugging

### Component Status Tool

Use the debug tool to check component status:

```bash
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "debug-1",
       "method": "tools/call",
       "params": {
         "name": "debug/component",
         "arguments": {}
       }
     }'
```

### Log Categories

- `org.moqui.mcp.McpSdkServicesServlet` - Servlet operations
- `org.moqui.mcp.McpServices` - Service operations
- `io.modelcontextprotocol.sdk` - MCP SDK operations

### Audit Reports

Query audit records:

```sql
SELECT artifactName, COUNT(*) as callCount, 
       AVG(runningTimeMillis) as avgTime,
       COUNT(CASE WHEN wasError = 'Y' THEN 1 END) as errorCount
FROM moqui.server.ArtifactHit
WHERE artifactType = 'MCP'
GROUP BY artifactName
ORDER BY callCount DESC;
```

## Comparison: Approaches

| Feature | Custom Servlet | SDK + Custom Logic | SDK + Services |
|---------|----------------|-------------------|-----------------|
| **Protocol Compliance** | Manual | SDK | SDK |
| **Business Logic** | Custom | Custom | Services |
| **Code Reuse** | Low | Medium | High |
| **Testing** | Complex | Medium | Simple |
| **Maintenance** | High | Medium | Low |
| **Security** | Custom | Custom | Moqui |
| **Auditing** | Custom | Custom | Built-in |
| **Performance** | Unknown | Good | Optimized |

## Migration Guide

### From Custom Implementation

1. **Add MCP SDK dependency** to build.gradle
2. **Deploy McpServices.xml** (if not already present)
3. **Replace servlet** with `McpSdkServicesServlet`
4. **Update web.xml** configuration
5. **Test existing clients** for compatibility
6. **Update documentation** with new tool names

### Client Compatibility

The SDK + Services approach maintains compatibility:

- **Same JSON-RPC 2.0 protocol**
- **Enhanced tool interfaces** (more services available)
- **Better error responses** (standardized)
- **Improved security** (Moqui integration)

## Best Practices

### 1. Service Design

- **Keep services focused** on single responsibilities
- **Use proper error handling** with meaningful messages
- **Document parameters** with descriptions and types
- **Validate inputs** before processing

### 2. Security

- **Always check permissions** before operations
- **Use parameterized queries** to prevent injection
- **Log all access** for audit trails
- **Sanitize outputs** to prevent data leakage

### 3. Performance

- **Limit result sets** to prevent memory issues
- **Use appropriate indexes** for entity queries
- **Cache frequently accessed data**
- **Monitor execution times** for optimization

### 4. Error Handling

- **Provide meaningful error messages**
- **Include context** for debugging
- **Log errors appropriately**
- **Graceful degradation** for failures

## Future Enhancements

### Planned Features

1. **Async Services** - Support for long-running operations
2. **Streaming Resources** - Large dataset streaming
3. **Tool Categories** - Organize services by domain
4. **Dynamic Registration** - Runtime service addition
5. **Enhanced Prompts** - More sophisticated prompt templates

### Advanced Integration

1. **Event-Driven Updates** - Real-time entity change notifications
2. **Workflow Integration** - MCP-triggered Moqui workflows
3. **Multi-tenancy** - Tenant-aware MCP operations
4. **GraphQL Support** - GraphQL as alternative to entity access

## References

- [MCP Java SDK Documentation](https://modelcontextprotocol.io/sdk/java/mcp-server)
- [MCP Protocol Specification](https://modelcontextprotocol.io/specification/)
- [Moqui Framework Documentation](https://moqui.org/docs/)
- [Moqui Service Engine](https://moqui.org/docs/docs/moqui-service-engine)
- [Moqui Entity Engine](https://moqui.org/docs/docs/moqui-entity-engine)