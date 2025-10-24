# Moqui MCP Component

Model Context Protocol (MCP) server component for AI-Moqui integration, providing seamless connectivity between AI systems and the Moqui enterprise framework.

## Overview

The moqui-mcp component implements the Model Context Protocol (MCP) server, enabling AI systems to securely interact with Moqui entities, services, and business logic through a standardized protocol interface.

## Features

### Core MCP Functionality
- **MCP Protocol Server**: Full implementation of the Model Context Protocol specification
- **Entity Access Tools**: Secure CRUD operations on Moqui entities with permission validation
- **Service Execution Tools**: Invoke Moqui services with parameter validation and security checks
- **Search & Discovery**: Advanced search capabilities across entities and services
- **Session Management**: Secure session handling with authentication and authorization

### Security & Administration
- **Multi-layer Security**: Entity-level, service-level, and API-level security controls
- **Audit Logging**: Comprehensive audit trail for all MCP operations
- **Rate Limiting**: Configurable rate limits to prevent abuse
- **Session Management**: Configurable session timeouts and cleanup
- **User Groups**: Pre-configured MCP_ADMIN and MCP_USER roles

### Integration Features
- **Web Dashboard**: Administrative interface for monitoring and configuration
- **REST API**: RESTful endpoints for MCP server management
- **Configuration Management**: Flexible property-based configuration
- **Database Integration**: Full integration with Moqui's transactional database

## Installation

### Prerequisites
- Moqui Framework 3.0+
- Java 17+
- Database (MySQL, PostgreSQL, or supported Moqui databases)

### Setup Instructions

1. **Component Deployment**
   ```bash
   # Copy component to Moqui runtime directory
   cp -r moqui-mcp /path/to/moqui/runtime/component/
   ```

2. **Database Initialization**
   - Component entities are automatically created on startup
   - Seed data includes default configurations and user groups

3. **Configuration**
   - Edit `MoquiConf.xml` for screen integration
   - Configure properties in `component.xml` as needed

4. **Restart Moqui**
   ```bash
   ./gradlew run
   ```

## Configuration

### Server Configuration
```xml
<property-list name="mcp.server">
    <property name="enabled" value="true"/>
    <property name="port" value="8081"/>
    <property name="session.timeout.minutes" value="60"/>
    <property name="rate.limit.requests.per.minute" value="100"/>
    <property name="audit.enabled" value="true"/>
    <property name="max.session.duration.minutes" value="480"/>
</property-list>
```

### LLM Provider Configuration
```xml
<property-list name="mcp.llm">
    <property name="provider.url" value="${mcp_llm_provider_url}"/>
    <property name="provider.api_key" value="${mcp_llm_provider_api_key}"/>
    <property name="provider.model" value="${mcp_llm_provider_model:gpt-4}"/>
    <property name="timeout.seconds" value="30"/>
    <property name="max.tokens" value="4096"/>
    <property name="temperature" value="0.7"/>
</property-list>
```

### Security Configuration
```xml
<property-list name="mcp.security">
    <property name="require.https" value="false"/>
    <property name="allowed.origins" value="*"/>
    <property name="max.request.size.mb" value="10"/>
    <property name="enable.cors" value="true"/>
</property-list>
```

## Usage

### Accessing the MCP Dashboard

1. **Login to Moqui** as an administrator
2. **Navigate to Apps** → **MCP Server**
3. **Access Dashboard** at `/apps/mcp/`

### Available MCP Tools

#### Entity Tools
- `entity_find`: Query entities with filters and pagination
- `entity_find_one`: Retrieve single entity record
- `entity_create`: Create new entity records
- `entity_update`: Modify existing entity records
- `entity_delete`: Remove entity records

#### Service Tools
- `service_call`: Invoke Moqui services with parameters
- `service_list`: Discover available services
- `service_help`: Get service documentation

#### Search Tools
- `search_entities`: Full-text search across entities
- `search_services": Search service definitions
- `search_help": Advanced search capabilities

### Example MCP Session

```json
{
  "session_id": "mcp_session_12345",
  "user_id": "admin",
  "created_time": "2024-01-15T10:30:00Z",
  "tools_available": [
    "entity_find",
    "entity_create", 
    "service_call",
    "search_entities"
  ],
  "permissions": ["MCP_ADMIN"]
}
```

## Security Model

### Permission Levels

#### MCP_ADMIN
- Full access to all MCP tools and entities
- User and session management capabilities
- System configuration and monitoring
- Audit log access

#### MCP_USER
- Limited entity access based on Moqui permissions
- Service execution with restrictions
- Basic search capabilities
- Own session management only

### Entity Security
- Respects Moqui's built-in entity permissions
- Additional MCP-specific access controls
- Field-level security for sensitive data

### Service Security
- Service-level permission validation
- Parameter sanitization and validation
- Execution timeout controls

## API Reference

### REST Endpoints

#### Session Management
- `POST /mcp/api/sessions` - Create new session
- `GET /mcp/api/sessions/{id}` - Get session details
- `DELETE /mcp/api/sessions/{id}` - Terminate session

#### Tool Execution
- `POST /mcp/api/tools/{tool_name}` - Execute MCP tool
- `GET /mcp/api/tools` - List available tools

#### Administration
- `GET /mcp/api/status` - Server status and statistics
- `GET /mcp/api/audit` - Audit log access
- `GET /mcp/api/config` - Configuration details

### MCP Protocol Messages

#### Initialize Request
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "tools": {}
    },
    "clientInfo": {
      "name": "ai-client",
      "version": "1.0.0"
    }
  }
}
```

#### Tool Call Request
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "entity_find",
    "arguments": {
      "entity": "Product",
      "filter": {"productId": "PROD-001"},
      "limit": 10
    }
  }
}
```

## Monitoring & Troubleshooting

### Dashboard Metrics
- Active sessions count
- Request rate and response times
- Error rates and types
- Resource utilization

### Audit Logs
All MCP operations are logged with:
- Timestamp and session ID
- User identification
- Tool called and parameters
- Execution results and errors

### Common Issues

#### Session Timeouts
- Check `session.timeout.minutes` configuration
- Verify user activity and session cleanup

#### Permission Denied
- Verify user group assignments
- Check entity and service permissions
- Review MCP security configuration

#### Performance Issues
- Monitor database query performance
- Check rate limiting settings
- Review audit log volume

## Development

### Component Structure
```
moqui-mcp/
├── component.xml          # Component definition
├── MoquiConf.xml          # Screen facade configuration
├── entity/                # Entity definitions
│   └── McpServerEntities.xml
├── service/               # Service implementations
│   ├── org/moqui/mcp/
│   └── mcp-tools/
├── screen/                # Screen definitions
│   ├── McpRoot.xml
│   └── McpServerScreens.xml
├── data/                  # Seed data
│   └── McpServerData.xml
└── webapp/               # Web application resources
```

### Adding New Tools

1. **Define Service** in `service/mcp-tools/`
2. **Add Tool Registration** in MCP tool services
3. **Update Permissions** in component configuration
4. **Add Documentation** to this README

### Testing

```bash
# Run component tests
./gradlew test --tests "*Mcp*"

# Test MCP protocol
curl -X POST http://localhost:8081/mcp/api \
  -H "Content-Type: application/json" \
  -d @test_mcp_request.json
```

## Contributing

### Development Guidelines
- Follow Moqui component conventions
- Implement proper security controls
- Add comprehensive audit logging
- Include unit and integration tests
- Update documentation

### Pull Request Process
1. Fork the repository
2. Create feature branch
3. Implement changes with tests
4. Update documentation
5. Submit pull request with description

## License

This component is released under CC0 1.0 Universal plus a Grant of Patent License, placing it in the public domain.

## Support

### Documentation
- [Moqui Framework Documentation](https://moqui.org/docs/)
- [MCP Protocol Specification](https://modelcontextprotocol.io/)
- Component AGENTS.md for detailed implementation guide

### Community
- [Moqui Community Forum](https://moqui.org/community/)
- [GitHub Issues](https://github.com/moqui/moqui-mcp/issues)

### Version History

#### v1.0.0 (Current)
- Initial MCP server implementation
- Entity and service tools
- Security and audit framework
- Web dashboard interface
- REST API endpoints

---

**Note**: This component requires proper security configuration before production use. Always review and test security settings in a development environment first.