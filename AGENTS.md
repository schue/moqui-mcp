# Moqui MCP Server Implementation Guide

This guide focuses on implementing the Model Context Protocol (MCP) server component for Moqui. The MCP server provides a generic, secure interface for AI agents to interact with Moqui data and services.

## Component Overview

**Component Name:** `moqui-mcp`
**Purpose:** Generic MCP protocol server for AI-Moqui integration
**Architecture:** Standalone Moqui component exposing MCP-compliant services

## Core Architecture

### MCP Protocol Layer
```
AI Agent → MCP Protocol → moqui-mcp Services → Moqui Framework
```

### Security Model
1. **User Context Tokens**: Temporary tokens representing user security context
2. **Service Isolation**: Each MCP service validates user permissions
3. **Audit Logging**: All MCP interactions logged via Moqui's audit system
4. **Rate Limiting**: Built-in protection against abuse

## Component Structure

```
moqui-mcp/
├── component.xml                    # Component configuration
├── entity/
│   └── McpServerEntities.xml       # MCP-specific entities
├── service/
│   ├── org/moqui/mcp/
│   │   ├── McpProtocolServices.xml # MCP protocol implementation
│   │   ├── McpToolServices.xml     # Core MCP tools
│   │   └── McpSecurityServices.xml # Security context management
│   └── mcp-tools/                  # Individual tool implementations
│       ├── EntityTools.xml         # Entity manipulation tools
│       ├── ServiceTools.xml        # Service execution tools
│       └── SearchTools.xml         # Search and query tools
├── screen/
│   └── McpServerScreens.xml        # MCP server management UI
├── data/
│   └── McpServerData.xml           # Seed data and configuration
└── src/
    └── main/java/org/moqui/mcp/    # Java utilities if needed
```

## Entity Definitions

### Core MCP Entities

```xml
<!-- MCP Session Management -->
<entity entity-name="McpSession" package="org.moqui.mcp">
    <field name="sessionId" type="id" is-pk="true"/>
    <field name="userAccountId" type="id"/>
    <field name="contextToken" type="text-medium"/>
    <field name="statusId" type="id"/>
    <field name="createdDate" type="date-time"/>
    <field name="lastAccessedDate" type="date-time"/>
    <field name="expiresDate" type="date-time"/>
</entity>

<!-- MCP Tool Call Tracking -->
<entity entity-name="McpToolCall" package="org.moqui.mcp">
    <field name="toolCallId" type="id" is-pk="true"/>
    <field name="sessionId" type="id"/>
    <field name="toolName" type="text-medium"/>
    <field name="parameters" type="text-long"/>
    <field name="result" type="text-very-long"/>
    <field name="statusId" type="id"/>
    <field name="executionTime" type="number-decimal"/>
</entity>
```

## Service Implementation

### MCP Protocol Services

#### Session Management
```xml
<service verb="create" noun="McpSession" authenticate="true">
    <description>Create MCP session with user context</description>
    <in-parameters>
        <parameter name="userAccountId" type="id" required="true"/>
        <parameter name="durationMinutes" type="number-integer" default="60"/>
    </in-parameters>
    <out-parameters>
        <parameter name="sessionId" type="id"/>
        <parameter name="contextToken" type="text-medium"/>
    </out-parameters>
    <actions>
        <!-- Create session record -->
        <!-- Generate secure context token -->
        <!-- Set expiration -->
    </actions>
</service>
```

#### Core MCP Tools

##### Entity Tools
```xml
<service verb="find" noun="McpEntity" authenticate="false">
    <description>Secure entity find through MCP</description>
    <in-parameters>
        <parameter name="entityName" type="text-medium" required="true"/>
        <parameter name="condition" type="text-long"/>
        <parameter name="contextToken" type="text-medium" required="true"/>
        <parameter name="limit" type="number-integer" default="100"/>
    </in-parameters>
    <out-parameters>
        <parameter name="resultList" type="List"/>
        <parameter name="count" type="number-integer"/>
    </out-parameters>
    <actions>
        <!-- Validate context token -->
        <!-- Check user permissions -->
        <!-- Execute entity find -->
        <!-- Log tool call -->
    </actions>
</service>
```

##### Service Tools
```xml
<service verb="call" noun="McpService" authenticate="false">
    <description>Secure service call through MCP</description>
    <in-parameters>
        <parameter name="serviceName" type="text-medium" required="true"/>
        <parameter name="parameters" type="text-long"/>
        <parameter name="contextToken" type="text-medium" required="true"/>
    </in-parameters>
    <out-parameters>
        <parameter name="result" type="Map"/>
    </out-parameters>
    <actions>
        <!-- Validate context token -->
        <!-- Check service permissions -->
        <!-- Execute service call -->
        <!-- Log tool call -->
    </actions>
</service>
```

## Security Implementation

### Context Token Management
```groovy
// Example token validation logic
def validateContextToken(String token) {
    def session = ec.entity.find("McpSession")
        .condition("contextToken", token)
        .condition("statusId", "McsActive")
        .condition("expiresDate", ec.user.now, ComparisonOperator.GREATER_THAN)
        .one()
    
    if (!session) {
        throw new AuthenticationException("Invalid or expired context token")
    }
    
    // Update last accessed
    ec.service.sync("update#McpSession", [sessionId: session.sessionId, 
                                          lastAccessedDate: ec.user.now])
    
    // Set user context for this thread
    ec.user.setUserIdByToken(session.userAccountId)
    
    return session
}
```

### Permission Checking
```groovy
def checkEntityPermission(String entityName, String operation) {
    def artifactName = "entity:${entityName}"
    def hasPermission = ec.user.hasPermission(artifactName, operation)
    
    if (!hasPermission) {
        throw new AuthorizationException("User does not have ${operation} permission on ${entityName}")
    }
}
```

## Configuration

### MoquiConf.xml Settings
```xml
<property name="mcp.server.enabled" value="true"/>
<property name="mcp.server.port" value="8081"/>
<property name="mcp.session.timeout.minutes" value="60"/>
<property name="mcp.rate.limit.requests.per.minute" value="100"/>
<property name="mcp.audit.enabled" value="true"/>
```

### LLM Provider Configuration
```xml
<property name="mcp.llm.provider.url" value="https://api.openai.com/v1"/>
<property name="mcp.llm.provider.api_key" value="${OPENAI_API_KEY}"/>
<property name="mcp.llm.provider.model" value="gpt-4"/>
<property name="mcp.llm.timeout.seconds" value="30"/>
```

## Testing Strategy

### Unit Tests
- Context token validation
- Permission checking
- Entity find operations
- Service call execution

### Integration Tests
- End-to-end MCP protocol flow
- Security context propagation
- Error handling and recovery

### Security Tests
- Token hijacking attempts
- Permission bypass attempts
- Rate limiting enforcement
- Audit trail completeness

## Monitoring & Logging

### Key Metrics
- Session creation/expiration rate
- Tool call frequency and performance
- Error rates by tool type
- User activity patterns

### Audit Events
- Session creation/destruction
- Tool call initiation/completion
- Permission denials
- Security violations

## Implementation Checklist

### Phase 1: Core Infrastructure
- [ ] Component skeleton created
- [ ] Basic entities defined
- [ ] Session management services
- [ ] Context token validation

### Phase 2: MCP Tools
- [ ] Entity find tool
- [ ] Service call tool
- [ ] Search tool
- [ ] Permission validation

### Phase 3: Security & Monitoring
- [ ] Rate limiting
- [ ] Audit logging
- [ ] Error handling
- [ ] Performance monitoring

### Phase 4: Integration & Testing
- [ ] Unit tests
- [ ] Integration tests
- [ ] Security tests
- [ ] Performance tests

## Usage Examples

### Basic Entity Query
```json
{
    "tool": "findEntity",
    "parameters": {
        "entityName": "mantle.party.Party",
        "condition": {"partyTypeId": "PERSON"},
        "limit": 10
    },
    "contextToken": "abc123..."
}
```

### Service Execution
```json
{
    "tool": "callService",
    "parameters": {
        "serviceName": "create#mantle.party.Party",
        "parameters": {
            "partyTypeId": "PERSON",
            "description": "New Person"
        }
    },
    "contextToken": "abc123..."
}
```

## Troubleshooting

### Common Issues
1. **Token Validation Failures**: Check expiration and user status
2. **Permission Denials**: Verify user has required artifact permissions
3. **Performance Issues**: Monitor database queries and consider caching
4. **Memory Leaks**: Ensure proper session cleanup

### Debug Tools
- MCP session viewer screen
- Tool call log viewer
- Permission checker utility
- Performance monitoring dashboard

---

**This MCP server component provides the foundation for AI integration with Moqui.** 
It can be used independently by any AI component, including moqui-ai, and follows Moqui's security and architectural patterns.
