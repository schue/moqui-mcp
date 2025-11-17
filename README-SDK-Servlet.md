# Moqui MCP SDK Servlet

This document describes the MCP servlet implementation using the official Java MCP SDK library (`io.modelcontextprotocol.sdk`).

## Overview

The `McpSdkSseServlet` uses the official MCP Java SDK to provide standards-compliant MCP server functionality with:

- **Full MCP protocol compliance** using official SDK
- **SSE transport** with proper session management
- **Built-in tool/resource/prompt registration** 
- **JSON schema validation** for all inputs
- **Standard error handling** and response formatting
- **Async support** for scalable connections

## Architecture

### MCP SDK Integration

The servlet uses these key MCP SDK components:

- `HttpServletSseServerTransportProvider` - SSE transport implementation
- `McpSyncServer` - Synchronous MCP server API
- `McpServerFeatures.SyncToolSpecification` - Tool definitions
- `McpServerFeatures.SyncResourceSpecification` - Resource definitions  
- `McpServerFeatures.SyncPromptSpecification` - Prompt definitions

### Endpoint Structure

```
/mcp/sse           - SSE endpoint for server-to-client events (handled by SDK)
/mcp/message        - Message endpoint for client-to-server requests (handled by SDK)
```

## Dependencies

### Build Configuration

Add to `build.gradle`:

```gradle
dependencies {
    // MCP Java SDK
    implementation 'io.modelcontextprotocol.sdk:mcp-sdk:1.0.0'
    
    // Jackson for JSON handling (required by MCP SDK)
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.15.2'
    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2'
    
    // Servlet API
    compileOnly 'javax.servlet:javax.servlet-api:4.0.1'
}
```

### Maven Dependencies

```xml
<dependencies>
    <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp-sdk</artifactId>
        <version>1.0.0</version>
    </dependency>
    
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.15.2</version>
    </dependency>
    
    <dependency>
        <groupId>com.fasterxml.jackson.datatype</groupId>
        <artifactId>jackson-datatype-jsr310</artifactId>
        <version>2.15.2</version>
    </dependency>
</dependencies>
```

## Configuration

### Web.xml Configuration

```xml
<servlet>
    <servlet-name>McpSdkSseServlet</servlet-name>
    <servlet-class>org.moqui.mcp.McpSdkSseServlet</servlet-class>
    
    <init-param>
        <param-name>moqui-name</param-name>
        <param-value>moqui-mcp-2</param-value>
    </init-param>
    
    <async-supported>true</async-supported>
    <load-on-startup>1</load-on-startup>
</servlet>

<servlet-mapping>
    <servlet-name>McpSdkSseServlet</servlet-name>
    <url-pattern>/mcp/*</url-pattern>
</servlet-mapping>
```

## Available Tools

### Entity Tools

| Tool | Description | Parameters |
|------|-------------|------------|
| `EntityFind` | Query entities | `entity` (required), `fields`, `constraint`, `limit`, `offset` |
| `EntityCreate` | Create entity records | `entity` (required), `fields` (required) |
| `EntityUpdate` | Update entity records | `entity` (required), `fields` (required), `constraint` |
| `EntityDelete` | Delete entity records | `entity` (required), `constraint` |

### Service Tools

| Tool | Description | Parameters |
|------|-------------|------------|
| `ServiceCall` | Execute Moqui services | `service` (required), `parameters` |
| `SystemStatus` | Get system statistics | `includeMetrics`, `includeCache` |

## Available Resources

### Entity Resources

- `entity://` - List all entities
- `entity://EntityName` - Get specific entity definition

**Example:**
```bash
# List all entities
curl -H "Accept: application/json" \
     http://localhost:8080/mcp/resources?uri=entity://

# Get specific entity
curl -H "Accept: application/json" \
     http://localhost:8080/mcp/resources?uri=entity://moqui.security.UserAccount
```

## Available Prompts

### Entity Query Prompt

Generate Moqui entity queries:

```json
{
  "name": "entity-query",
  "arguments": {
    "entity": "mantle.order.OrderHeader",
    "purpose": "Find recent orders",
    "fields": "orderId, orderDate, grandTotal"
  }
}
```

### Service Definition Prompt

Generate Moqui service definitions:

```json
{
  "name": "service-definition", 
  "arguments": {
    "serviceName": "create#OrderItem",
    "description": "Create a new order item",
    "parameters": "{\"orderId\":\"string\",\"productId\":\"string\",\"quantity\":\"number\"}"
  }
}
```

## Usage Examples

### Client Connection

```javascript
// Using MCP SDK client
import { McpClient } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const transport = new StdioClientTransport({
  command: 'curl',
  args: ['-X', 'POST', 'http://localhost:8080/mcp/message']
});

const client = new McpClient(
  transport,
  {
    name: "Moqui Client",
    version: "1.0.0"
  }
);

await client.connect();

// List tools
const tools = await client.listTools();
console.log('Available tools:', tools.tools);

// Call a tool
const result = await client.callTool({
  name: "EntityFind",
  arguments: {
    entity: "moqui.security.UserAccount",
    limit: 10
  }
});
console.log('Tool result:', result);
```

### Raw HTTP Client

```bash
# Initialize connection
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "init-1",
       "method": "initialize",
       "params": {
         "clientInfo": {
           "name": "Test Client",
           "version": "1.0.0"
         }
       }
     }'

# List tools
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "tools-1",
       "method": "tools/list",
       "params": {}
     }'

# Call EntityFind tool
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "call-1",
       "method": "tools/call",
       "params": {
         "name": "EntityFind",
         "arguments": {
           "entity": "moqui.security.UserAccount",
           "fields": ["username", "emailAddress"],
           "limit": 5
         }
       }
     }'
```

## Tool Implementation Details

### EntityFind Tool

```groovy
def entityFindTool = new McpServerFeatures.SyncToolSpecification(
    new Tool("EntityFind", "Find entities in Moqui using entity queries", """
        {
            "type": "object",
            "properties": {
                "entity": {"type": "string", "description": "Entity name"},
                "fields": {"type": "array", "items": {"type": "string"}, "description": "Fields to select"},
                "constraint": {"type": "string", "description": "Constraint expression"},
                "limit": {"type": "number", "description": "Maximum results"},
                "offset": {"type": "number", "description": "Results offset"}
            },
            "required": ["entity"]
        }
        """),
    { exchange, arguments ->
        return executeWithEc { ec ->
            String entityName = arguments.get("entity") as String
            List<String> fields = arguments.get("fields") as List<String>
            String constraint = arguments.get("constraint") as String
            Integer limit = arguments.get("limit") as Integer
            Integer offset = arguments.get("offset") as Integer
            
            def finder = ec.entity.find(entityName).selectFields(fields ?: ["*"])
            if (constraint) {
                finder.condition(constraint)
            }
            if (offset) {
                finder.offset(offset)
            }
            if (limit) {
                finder.limit(limit)
            }
            def result = finder.list()
            
            new CallToolResult([
                new TextContent("Found ${result.size()} records in ${entityName}: ${result}")
            ], false)
        }
    }
)
```

## Error Handling

The MCP SDK provides standardized error handling:

### JSON-RPC Errors

| Code | Description | Example |
|------|-------------|---------|
| `-32600` | Invalid Request | Malformed JSON-RPC |
| `-32601` | Method Not Found | Unknown method name |
| `-32602` | Invalid Params | Missing required parameters |
| `-32603` | Internal Error | Server-side exception |

### Tool Execution Errors

```groovy
return new CallToolResult([
    new TextContent("Error: " + e.message)
], true)  // isError = true
```

### Resource Errors

```groovy
throw new IllegalArgumentException("Entity not found: ${entityName}")
```

## Security

### Authentication Integration

The servlet integrates with Moqui's security framework:

```groovy
// Authenticate as admin for MCP operations
if (!ec.user?.userId) {
    try {
        ec.user.loginUser("admin", "admin")
    } catch (Exception e) {
        logger.warn("MCP Admin login failed: ${e.message}")
    }
}
```

### CORS Support

```groovy
private static boolean handleCors(HttpServletRequest request, HttpServletResponse response, String webappName) {
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
```

## Monitoring and Logging

### Log Categories

- `org.moqui.mcp.McpSdkSseServlet` - Servlet operations
- `io.modelcontextprotocol.sdk` - MCP SDK operations

### Key Log Messages

```
INFO  - McpSdkSseServlet initialized for webapp moqui-mcp-2
INFO  - MCP Server started with SSE transport
INFO  - Registered 6 Moqui tools
INFO  - Registered Moqui resources
INFO  - Registered Moqui prompts
```

## Performance Considerations

### Connection Management

- **Async processing** for non-blocking operations
- **Connection pooling** handled by servlet container
- **Session management** provided by MCP SDK
- **Graceful shutdown** on servlet destroy

### Memory Usage

- **Tool definitions** loaded once at startup
- **ExecutionContext** created per request
- **JSON parsing** handled by Jackson
- **SSE connections** managed efficiently by SDK

## Comparison: Custom vs SDK Implementation

| Feature | Custom Implementation | SDK Implementation |
|---------|---------------------|-------------------|
| **Protocol Compliance** | Manual implementation | Guaranteed by SDK |
| **Error Handling** | Custom code | Standardized |
| **JSON Schema** | Manual validation | Automatic |
| **SSE Transport** | Custom implementation | Built-in |
| **Session Management** | Manual code | Built-in |
| **Testing** | Custom tests | SDK tested |
| **Maintenance** | High effort | Low effort |
| **Standards Updates** | Manual updates | Automatic with SDK |

## Migration from Custom Servlet

### Benefits of SDK Migration

1. **Standards Compliance** - Guaranteed MCP protocol compliance
2. **Reduced Maintenance** - Less custom code to maintain
3. **Better Error Handling** - Standardized error responses
4. **Future Compatibility** - Automatic updates with SDK releases
5. **Testing** - SDK includes comprehensive test suite

### Migration Steps

1. **Add MCP SDK dependency** to build.gradle
2. **Replace servlet class** with `McpSdkSseServlet`
3. **Update web.xml** configuration
4. **Test existing clients** for compatibility
5. **Update documentation** with new endpoints

### Client Compatibility

The SDK implementation maintains compatibility with existing MCP clients:

- Same JSON-RPC 2.0 protocol
- Same tool/resource/prompt interfaces
- Same authentication patterns
- Better error responses and validation

## Troubleshooting

### Common Issues

1. **Dependency Conflicts**
   ```bash
   # Check for Jackson version conflicts
   ./gradlew dependencies | grep jackson
   ```

2. **Async Support Issues**
   ```xml
   <!-- Ensure async is enabled in web.xml -->
   <async-supported>true</async-supported>
   ```

3. **Servlet Container Compatibility**
   - Requires Servlet 3.0+ for async support
   - Test with Tomcat 8.5+, Jetty 9.4+, or similar

4. **MCP SDK Version**
   ```gradle
   // Use latest stable version
   implementation 'io.modelcontextprotocol.sdk:mcp-sdk:1.0.0'
   ```

### Debug Mode

Enable debug logging:

```xml
<Logger name="org.moqui.mcp.McpSdkSseServlet" level="DEBUG"/>
<Logger name="io.modelcontextprotocol.sdk" level="DEBUG"/>
```

## Future Enhancements

### Planned Features

1. **Async Tools** - Use `McpServerFeatures.AsyncToolSpecification`
2. **Streaming Resources** - Large data set streaming
3. **Tool Categories** - Organize tools by domain
4. **Dynamic Registration** - Runtime tool/resource addition
5. **Metrics Collection** - Performance and usage metrics

### Advanced Integration

1. **Spring Integration** - Use MCP SDK Spring starters
2. **WebFlux Support** - Reactive transport providers
3. **WebSocket Transport** - Alternative to SSE
4. **Load Balancing** - Multi-instance deployment

## References

- [MCP Java SDK Documentation](https://modelcontextprotocol.io/sdk/java/mcp-server)
- [MCP Protocol Specification](https://modelcontextprotocol.io/specification/)
- [Moqui Framework Documentation](https://moqui.org/docs/)
- [Servlet 3.0 Specification](https://jakarta.ee/specifications/servlet/3.0/)