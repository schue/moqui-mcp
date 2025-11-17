# Moqui MCP SSE Servlet

This document describes the new MCP SSE (Server-Sent Events) servlet implementation based on the official Java MCP SDK SSE Servlet approach.

## Overview

The `McpSseServlet` implements the MCP SSE transport specification using the traditional Servlet API, providing:

- **Asynchronous message handling** using Servlet async support
- **Session management** for multiple client connections  
- **Two types of endpoints**:
  - SSE endpoint (`/sse`) for server-to-client events
  - Message endpoint (`/mcp/message`) for client-to-server requests
- **Error handling** and response formatting
- **Graceful shutdown** support
- **Keep-alive pings** to maintain connections
- **Connection limits** to prevent resource exhaustion

## Architecture

### Endpoint Structure

```
/sse              - SSE endpoint for server-to-client events
/mcp/message      - Message endpoint for client-to-server requests
```

### Connection Flow

1. **Client connects** to `/sse` endpoint
2. **Server establishes** SSE connection with unique session ID
3. **Client sends** JSON-RPC messages to `/mcp/message`
4. **Server processes** messages and sends responses via SSE
5. **Keep-alive pings** maintain connection health

## Configuration

### Servlet Configuration Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `moqui-name` | - | Moqui webapp name (required) |
| `sseEndpoint` | `/sse` | SSE endpoint path |
| `messageEndpoint` | `/mcp/message` | Message endpoint path |
| `keepAliveIntervalSeconds` | `30` | Keep-alive ping interval |
| `maxConnections` | `100` | Maximum concurrent SSE connections |

### Web.xml Configuration

```xml
<servlet>
    <servlet-name>McpSseServlet</servlet-name>
    <servlet-class>org.moqui.mcp.McpSseServlet</servlet-class>
    
    <init-param>
        <param-name>moqui-name</param-name>
        <param-value>moqui-mcp-2</param-value>
    </init-param>
    
    <init-param>
        <param-name>keepAliveIntervalSeconds</param-name>
        <param-value>30</param-value>
    </init-param>
    
    <async-supported>true</async-supported>
</servlet>

<servlet-mapping>
    <servlet-name>McpSseServlet</servlet-name>
    <url-pattern>/sse/*</url-pattern>
</servlet-mapping>

<servlet-mapping>
    <servlet-name>McpSseServlet</servlet-name>
    <url-pattern>/mcp/message/*</url-pattern>
</servlet-mapping>
```

## Usage Examples

### Client Connection Flow

#### 1. Establish SSE Connection

```bash
curl -N -H "Accept: text/event-stream" \
     http://localhost:8080/sse
```

**Response:**
```
event: connect
data: {"type":"connected","sessionId":"uuid-123","timestamp":1234567890,"serverInfo":{"name":"Moqui MCP SSE Server","version":"2.0.0","protocolVersion":"2025-06-18"}}

event: ping
data: {"type":"ping","timestamp":1234567890,"connections":1}
```

#### 2. Initialize MCP Session

```bash
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
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "init-1",
  "result": {
    "protocolVersion": "2025-06-18",
    "capabilities": {
      "tools": {"listChanged": true},
      "resources": {"subscribe": true, "listChanged": true},
      "logging": {},
      "notifications": {}
    },
    "serverInfo": {
      "name": "Moqui MCP SSE Server",
      "version": "2.0.0"
    },
    "sessionId": "uuid-123"
  }
}
```

#### 3. List Available Tools

```bash
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{
       "jsonrpc": "2.0",
       "id": "tools-1",
       "method": "tools/list",
       "params": {}
     }'
```

#### 4. Call a Tool

```bash
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
           "limit": 10
         }
       }
     }'
```

### JavaScript Client Example

```javascript
class McpSseClient {
    constructor(baseUrl) {
        this.baseUrl = baseUrl;
        this.sessionId = null;
        this.eventSource = null;
        this.messageId = 0;
    }
    
    async connect() {
        // Establish SSE connection
        this.eventSource = new EventSource(`${this.baseUrl}/sse`);
        
        this.eventSource.addEventListener('connect', (event) => {
            const data = JSON.parse(event.data);
            console.log('Connected:', data);
        });
        
        this.eventSource.addEventListener('ping', (event) => {
            const data = JSON.parse(event.data);
            console.log('Ping:', data);
        });
        
        this.eventSource.addEventListener('initialized', (event) => {
            const data = JSON.parse(event.data);
            console.log('Server initialized:', data);
        });
        
        // Initialize MCP session
        const initResponse = await this.sendMessage('initialize', {
            clientInfo: {
                name: 'JavaScript Client',
                version: '1.0.0'
            }
        });
        
        this.sessionId = initResponse.sessionId;
        return initResponse;
    }
    
    async sendMessage(method, params = {}) {
        const id = `msg-${++this.messageId}`;
        const payload = {
            jsonrpc: "2.0",
            id: id,
            method: method,
            params: params
        };
        
        const response = await fetch(`${this.baseUrl}/mcp/message`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(payload)
        });
        
        return await response.json();
    }
    
    async listTools() {
        return await this.sendMessage('tools/list');
    }
    
    async callTool(name, arguments) {
        return await this.sendMessage('tools/call', {
            name: name,
            arguments: arguments
        });
    }
    
    disconnect() {
        if (this.eventSource) {
            this.eventSource.close();
        }
    }
}

// Usage
const client = new McpSseClient('http://localhost:8080');

client.connect().then(() => {
    console.log('MCP client connected');
    
    // List tools
    return client.listTools();
}).then(tools => {
    console.log('Available tools:', tools);
    
    // Call a tool
    return client.callTool('EntityFind', {
        entity: 'moqui.security.UserAccount',
        limit: 5
    });
}).then(result => {
    console.log('Tool result:', result);
}).catch(error => {
    console.error('MCP client error:', error);
});
```

## Features

### Session Management

- **Unique session IDs** generated for each connection
- **Connection tracking** with metadata (user agent, timestamps)
- **Automatic cleanup** on connection close/error
- **Connection limits** to prevent resource exhaustion

### Event Types

| Event Type | Description | Data |
|------------|-------------|------|
| `connect` | Initial connection established | Session info, server details |
| `ping` | Keep-alive ping | Timestamp, connection count |
| `initialized` | Server initialization notification | Session info, client details |
| `subscribed` | Subscription confirmation | Session, event type |
| `tool_result` | Tool execution result | Tool name, result data |
| `resource_update` | Resource change notification | Resource URI, change data |

### Error Handling

- **JSON-RPC 2.0 compliant** error responses
- **Connection error recovery** with automatic cleanup
- **Resource exhaustion protection** with connection limits
- **Graceful degradation** on service failures

### Security

- **CORS support** for cross-origin requests
- **Moqui authentication integration** with admin fallback
- **Session isolation** between clients
- **Input validation** for all requests

## Monitoring

### Connection Status

Get current server status:

```bash
curl http://localhost:8080/mcp/message
```

**Response:**
```json
{
  "serverInfo": {
    "name": "Moqui MCP SSE Server",
    "version": "2.0.0",
    "protocolVersion": "2025-06-18"
  },
  "connections": {
    "active": 3,
    "max": 100
  },
  "endpoints": {
    "sse": "/sse",
    "message": "/mcp/message"
  }
}
```

### Logging

The servlet provides detailed logging for:

- Connection establishment/cleanup
- Message processing
- Error conditions
- Performance metrics

Log levels:
- `INFO`: Connection events, message processing
- `WARN`: Connection errors, authentication issues
- `ERROR`: System errors, service failures
- `DEBUG`: Detailed request/response data

## Comparison with Existing Servlet

| Feature | MoquiMcpServlet | McpSseServlet |
|---------|------------------|---------------|
| **Transport** | HTTP POST/GET | SSE + HTTP |
| **Async Support** | Limited | Full async |
| **Session Management** | Basic | Advanced |
| **Real-time Events** | No | Yes |
| **Connection Limits** | No | Yes |
| **Keep-alive** | No | Yes |
| **Standards Compliance** | Custom | MCP SSE Spec |

## Migration Guide

### From MoquiMcpServlet to McpSseServlet

1. **Update web.xml** to use the new servlet
2. **Update client code** to handle SSE connections
3. **Modify endpoints** from `/mcp/rpc` to `/mcp/message`
4. **Add SSE handling** for real-time events
5. **Update authentication** if using custom auth

### Client Migration

**Old approach:**
```bash
curl -X POST http://localhost:8080/mcp/rpc \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"ping","id":1}'
```

**New approach:**
```bash
# 1. Establish SSE connection
curl -N -H "Accept: text/event-stream" http://localhost:8080/sse &

# 2. Send messages
curl -X POST http://localhost:8080/mcp/message \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"ping","id":1}'
```

## Troubleshooting

### Common Issues

1. **SSE Connection Fails**
   - Check async support is enabled in web.xml
   - Verify servlet container supports Servlet 3.0+
   - Check firewall/proxy settings

2. **Messages Not Received**
   - Verify session ID is valid
   - Check connection is still active
   - Review server logs for errors

3. **High Memory Usage**
   - Reduce `maxConnections` parameter
   - Check for connection leaks
   - Monitor session cleanup

4. **Authentication Issues**
   - Verify Moqui security configuration
   - Check admin user credentials
   - Review webapp security settings

### Debug Mode

Enable debug logging by adding to log4j2.xml:

```xml
<Logger name="org.moqui.mcp.McpSseServlet" level="DEBUG" additivity="false">
    <AppenderRef ref="Console"/>
</Logger>
```

## Performance Considerations

### Connection Scaling

- **Default limit**: 100 concurrent connections
- **Memory usage**: ~1KB per connection
- **CPU overhead**: Minimal for idle connections
- **Network bandwidth**: Low (keep-alive pings only)

### Optimization Tips

1. **Adjust keep-alive interval** based on network conditions
2. **Monitor connection counts** and adjust limits
3. **Use connection pooling** for high-frequency clients
4. **Implement backpressure** for high-volume scenarios

## Future Enhancements

Planned improvements:

1. **WebSocket support** as alternative to SSE
2. **Message queuing** for offline clients
3. **Load balancing** support for multiple instances
4. **Advanced authentication** with token-based auth
5. **Metrics and monitoring** integration
6. **Message compression** for large payloads

## References

- [MCP Specification](https://modelcontextprotocol.io/)
- [Java MCP SDK](https://modelcontextprotocol.io/sdk/java/mcp-server)
- [Servlet 3.0 Specification](https://jakarta.ee/specifications/servlet/3.0/)
- [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)