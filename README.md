# Moqui MCP Server v0.2.0

## Overview
Simplified MCP server that uses Moqui's native service engine directly, eliminating need for manual tool registry management.

## Evolution from v0.1.0 (moqui-mcp) to v0.2.0 (moqui-mcp-2)

### Architectural Problems Identified in v0.1.0

The original moqui-mcp implementation had significant architectural duplications with Moqui's native systems:

1. **McpToolRegistry vs Service Engine**
   - Manual tool registry duplicated Moqui's service discovery
   - Required manual maintenance of tool definitions
   - Added unnecessary database queries and complexity

2. **McpAuditLog vs ArtifactHit**
   - Custom audit logging duplicated Moqui's proven ArtifactHit system
   - Missed built-in performance monitoring and aggregation
   - Separate audit trail instead of unified system-wide analytics

3. **McpSession vs Visit Entity**
   - Custom session management duplicated Moqui's Visit entity
   - Lost geo-location, referrer tracking, and other built-in features
   - Separate session lifecycle instead of leveraging existing patterns

4. **McpToolCall vs ArtifactHit**
   - Custom tool call tracking duplicated ArtifactHit functionality
   - Manual performance tracking instead of automatic aggregation
   - Separate error handling and logging

### v0.2.0 Architectural Solutions

**Complete Elimination of Duplications**:
- ❌ McpToolRegistry → ✅ Service Engine (`ec.service.getServiceNames()`)
- ❌ McpAuditLog → ✅ ArtifactHit (native audit & performance)
- ❌ McpSession → ✅ Visit Entity (extended with MCP fields)
- ❌ McpToolCall → ✅ ArtifactHit (with `artifactType="MCP"`)

**Key Architectural Changes**:

1. **Native Service Discovery**
   ```groovy
   // Before: Query McpToolRegistry
   def tools = ec.entity.find("McpToolRegistry").list()
   
   // After: Direct service engine access
   def allServiceNames = ec.service.getServiceNames()
   def hasPermission = ec.service.hasPermission(serviceName)
   ```

2. **Unified Session Management**
   ```xml
   <!-- Before: Custom McpSession entity -->
   <entity entity-name="McpSession" package="org.moqui.mcp">
   
   <!-- After: Extend native Visit entity -->
   <extend-entity entity-name="Visit" package="moqui.server">
       <field name="mcpContextToken" type="text-medium"/>
       <field name="mcpStatusId" type="id"/>
       <field name="mcpExpiresDate" type="date-time"/>
   </extend-entity>
   ```

3. **System-Wide Analytics**
   ```sql
   -- Before: Separate MCP tracking
   SELECT * FROM McpToolCall ORDER BY startTime DESC;
   SELECT * FROM McpAuditLog WHERE severity = 'McpSeverityWarning';
   
   -- After: Unified system tracking
   SELECT * FROM moqui.server.ArtifactHit 
   WHERE artifactType = 'MCP' ORDER BY startDateTime DESC;
   SELECT * FROM moqui.server.ArtifactHitReport 
   WHERE artifactType = 'MCP';
   ```

### Benefits Achieved

**Zero Maintenance**:
- No manual tool registry updates
- Automatic reflection of actual Moqui services
- No custom audit infrastructure to maintain

**Maximum Performance**:
- Direct service engine access (no extra lookups)
- Native caching and optimization
- Built-in performance monitoring

**Unified Analytics**:
- MCP operations visible in system-wide reports
- Automatic aggregation via ArtifactHitBin
- Standard reporting tools and dashboards

**Architectural Purity**:
- Follows Moqui's established patterns
- Leverages proven native systems
- REST service pattern (MCP as service invocation layer)

### Technical Implementation

**MCP Operation Flow**:
1. Session creation → `Visit` record with MCP extensions
2. Tool discovery → `ec.service.getServiceNames()` + permission check
3. Tool execution → Direct service call + `ArtifactHit` creation
4. Analytics → Native `ArtifactHitReport` aggregation

**Data Model**:
- Sessions: `moqui.server.Visit` (extended)
- Operations: `moqui.server.ArtifactHit` (filtered)
- Performance: `moqui.server.ArtifactHitBin` (automatic)
- Security: `moqui.security.UserGroupPermission` (native)

This evolution represents a complete architectural alignment with Moqui's core design principles while maintaining full MCP functionality.

## Quick Start

### 1. Add Component to Moqui
Add to your `MoquiConf.xml`:
```xml
<component name="moqui-mcp-2" location="moqui-mcp-2/"/>
```

### 2. Start Moqui
```bash
./gradlew run
```

### 3. Test MCP Server
```bash
# Health check
curl http://localhost:8080/mcp-2/health

# Create session
curl -X POST http://localhost:8080/mcp-2/session \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin"}'
```

## Integration with Opencode

### MCP Skill Configuration
Add to your Opencode configuration:

```json
{
  "skills": [
    {
      "name": "moqui-mcp",
      "type": "mcp",
      "endpoint": "http://localhost:8080/mcp-2",
      "auth": {
        "type": "session",
        "username": "admin",
        "password": "admin"
      },
      "description": "Query business data from Moqui ERP system"
    }
  ]
}
```

### Example Business Questions
Once configured, you can ask:

- "Show me all active customers"
- "What are recent orders for ACME Corp?"
- "List all products in inventory"
- "Find unpaid invoices"
- "Show user accounts with admin permissions"

## API Endpoints

### Session Management
- `POST /session` - Create session
- `POST /session/{visitId}/validate` - Validate session  
- `POST /session/{visitId}/terminate` - Terminate session

### Tool Operations
- `POST /tools/discover` - Discover available tools
- `POST /tools/validate` - Validate tool access
- `POST /tools/execute` - Execute service tool
- `POST /tools/entity/query` - Execute entity query

### System
- `GET /health` - Server health check

## Security Model

### Permission-Based Access
Tools are discovered based on user's `UserGroupPermission` settings:
- Services: `ec.service.hasPermission(serviceName)`
- Entities: `ec.user.hasPermission("entity:EntityName", "VIEW")`

### Audit Trail
All operations are tracked via Moqui's native `ArtifactHit` system:
- Automatic performance monitoring
- Built-in security event tracking
- Configurable persistence (database/ElasticSearch)
- Standard reporting via `ArtifactHitReport`

## Monitoring

### Check Logs
```bash
# MCP operations
tail -f moqui.log | grep "MCP"

# Session activity
tail -f moqui.log | grep "Visit"
```

### Database Queries
```sql
-- MCP operations via ArtifactHit
SELECT * FROM moqui.server.ArtifactHit 
WHERE artifactType = 'MCP' 
ORDER BY startDateTime DESC LIMIT 10;

-- MCP sessions (using Visit entity)
SELECT * FROM moqui.server.Visit 
WHERE mcpStatusId = 'McsActive' AND webappName = 'mcp-2';

-- MCP service operations
SELECT * FROM moqui.server.ArtifactHit 
WHERE artifactType = 'MCP' AND artifactSubType = 'Service'
ORDER BY startDateTime DESC LIMIT 10;

-- MCP entity operations  
SELECT * FROM moqui.server.ArtifactHit 
WHERE artifactType = 'MCP' AND artifactSubType = 'Entity'
ORDER BY startDateTime DESC LIMIT 10;

-- Performance analytics
SELECT * FROM moqui.server.ArtifactHitReport 
WHERE artifactType = 'MCP';
```

## Next Steps

1. **Test Basic Functionality**: Verify session creation and tool discovery
2. **Configure Opencode Skill**: Add MCP skill to your Opencode instance
3. **Test Business Queries**: Try natural language business questions
4. **Monitor Performance**: Check logs and audit tables
5. **Extend as Needed**: Add custom services/entities for specific business logic

## Architecture Benefits

- **Zero Maintenance**: No manual tool registry updates
- **Always Current**: Reflects actual Moqui services in real-time
- **Secure**: Uses Moqui's proven permission system
- **Performant**: Direct service engine access, no extra lookups
- **Unified Analytics**: All MCP operations tracked via native ArtifactHit
- **Built-in Reporting**: Uses ArtifactHitReport for standard analytics
- **Native Session Management**: Uses Moqui's Visit entity for session tracking
- **REST Service Pattern**: MCP is fundamentally a REST service invocation layer
- **Zero Custom Tracking**: Eliminates McpToolCall duplication with ArtifactHit

This MVP provides core functionality needed to integrate Moqui with Opencode as an MCP skill for business intelligence queries.