# Moqui MCP: AI in the Corporate Cockpit

## 🎥 **SEE IT WORK**

[![Moqui MCP Demo](https://img.youtube.com/vi/Tauucda-NV4/0.jpg)](https://www.youtube.com/watch?v=Tauucda-NV4)

**AI agents running real business operations.**

---

## What It Is

Moqui MCP puts AI into ERP systems - the software that runs every company. AI agents can now inhabit real corporate roles: purchasing, sales, HR, finance.

**Every product you touch passed through an inventory system. Now AI can touch it back.**

## Why It Matters

- **Real consequences**: Actions hit actual financials, inventory, supply chains
- **Real accountability**: P&L impact, compliance enforcement, audit trails  
- **Real operations**: Not simulations - live business processes
- **Real scale**: From corner stores to global supply chains

Foundation for autonomous business operations (ECA/SECA systems).

---

### 💬 **From the Maintainer**

[![GitHub Avatar](https://github.com/schue.png?s=20)](https://github.com/schue)

> *"Cut the junk. Ideas for JobSandbox integration?"*

**Your input shapes the roadmap.**

---

**⚠️ CONTAINERS & SECURITY REQUIRED ⚠️**

## 🚨 SECURITY WARNING 🚨

**NEVER deploy this with ADMIN access in production environments!** An LLM with ADMIN access can:

- **Execute ANY Moqui service** - including data deletion, user management, system configuration
- **Access ALL entities** - complete read/write access to every table in your database  
- **Render ANY screen** - bypass UI controls and access system internals directly
- **Modify user permissions** - escalate privileges, create admin accounts
- **Delete or corrupt data** - mass operations, database cleanup, etc.
- **Access financial data** - orders, payments, customer information, pricing
- **Bypass business rules** - direct service calls skip validation logic

## 🛡️ SAFE USAGE REQUIREMENTS

### **MANDATORY: Use Containers**
- **ALWAYS run in isolated containers** (Docker, Kubernetes, etc.)
- **NEVER expose directly to the internet** - use VPN/private networks only
- **Separate database instances** - never use production data
- **Regular backups** - assume the LLM will corrupt data eventually

### **MANDATORY: Limited User Accounts**
- **Create dedicated MCP users** with minimal required permissions
- **NEVER use ADMIN accounts** - create specific user groups for MCP access
- **Audit all access** - monitor service calls and data changes
- **Time-limited sessions** - auto-terminate inactive connections

### **MANDATORY: Network Isolation**
- **Firewall rules** - restrict to specific IP ranges
- **Rate limiting** - prevent runaway operations
- **Connection monitoring** - log all MCP traffic
- **Separate environments** - dev/test/staging isolation

## Overview

This implementation provides the **foundational bridge** between AI assistants and real-world business operations through Moqui ERP. It exposes the complete corporate operating system - screens, services, entities, workflows, and business rules - as MCP tools with **recursive discovery** to arbitrary depth.

**Think of this as giving AI agents actual jobs in real companies, with real responsibilities, real consequences, and real accountability.**

## Key Features (with Risk Assessment)

🔥 **Recursive Screen Discovery** - Automatically discovers ALL screens to arbitrary depth
- **Risk**: Exposes system admin screens, configuration screens, debug interfaces

🔥 **Security Model Bypass** - Uses ADMIN user context for many operations
- **Risk**: Can override user permissions, access restricted data

🔥 **Cross-Component Access** - Handles subscreens across all components
- **Risk**: No component isolation, can access entire system

🔥 **Direct Service Execution** - Can call ANY Moqui service directly
- **Risk**: Bypasses UI validation, business rules, audit trails

🔥 **Complete Entity Access** - Read/write access to ALL database tables
- **Risk**: Data corruption, privacy violations, mass deletion

🔥 **Session Hijacking** - Visit-based session management with user switching
- **Risk**: Can impersonate any user, including admins

🔥 **Test Data Creation** - Can create realistic-looking test data
- **Risk**: Pollutes production data, confuses reporting

## Architecture

The implementation consists of:

- **EnhancedMcpServlet** - Main MCP servlet handling JSON-RPC 2.0 protocol
- **McpServices** - Core services for initialization, tool discovery, and execution
- **Screen Discovery** - Recursive screen traversal with XML parsing
- **Security Integration** - Moqui artifact authorization system
- **Test Suite** - Comprehensive Java/Groovy tests

## Quick Start (FOR TESTING ONLY!)

### **⚠️ PREREQUISITES - READ FIRST!**

- **Docker or similar containerization** - MANDATORY
- **Isolated test environment** - NEVER use production data
- **Understanding of Moqui security model** - Required
- **Java 17+, Moqui Framework, Gradle** - Technical requirements
- **PopCommerce component** - For examples (optional)

### **SAFE INSTALLATION**

1. **Create isolated environment:**
```bash
# ALWAYS use containers
docker run -it --rm -v $(pwd):/app openjdk:17 bash
cd /app
```

2. **Clone and build:**
```bash
git clone https://github.com/schue/moqui-mcp.git
cd moqui-mcp
./gradlew build
```

3. **Start in container ONLY:**
```bash
# NEVER expose to internet
./gradlew run --daemon > ./server.log 2>&1 &
```

4. **Create LIMITED user account:**
```bash
# NEVER use ADMIN for MCP
# Create dedicated user with minimal permissions only
```

5. **Test with caution:**
```bash
./mcp.sh ping
# Start with read-only operations only
```

### Basic Usage

List available tools:
```bash
./mcp.sh tools
```

Call a specific screen:
```bash
./mcp.sh call screen_PopCommerce_screen_PopCommerceAdmin.Catalog
```

Search for products:
```bash
./mcp.sh call screen_PopCommerce_screen_PopCommerceAdmin_Catalog.Product_FindProduct
```

## Tool Examples

### Catalog Management
- `screen_PopCommerce_screen_PopCommerceAdmin.Catalog` - Main catalog screen
- `screen_PopCommerce_screen_PopCommerceAdmin.Catalog.Product` - Product management
- `screen_PopCommerce_screen_PopCommerceAdmin_Catalog.Product_FindProduct` - Product search
- `screen_PopCommerce_screen_PopCommerceAdmin_Catalog.Category_FindCategory` - Category search

### Order Management
- `screen_PopCommerce_screen_PopCommerceAdmin_Order.FindOrder` - Order lookup
- `screen_PopCommerce_screen_PopCommerceAdmin.Order.CreateOrder` - Create new order

### Customer Management
- `screen_PopCommerce_screen_PopCommerceRoot.Customer` - Customer management
- `screen_PopCommerce_screen_PopCommerceAdmin.Customer.FindCustomer` - Customer search

### Pricing
- `screen_PopCommerce_screen_PopCommerceAdmin_Catalog.Product_EditPrices` - Price management

## Configuration

### Server Configuration

The MCP server is configured via `MoquiConf.xml`:

```xml
<webapp name="webroot" http-port="8080">
    <servlet name="EnhancedMcpServlet" class="org.moqui.mcp.EnhancedMcpServlet" 
             load-on-startup="5" async-supported="true">
        <init-param name="keepAliveIntervalSeconds" value="30"/>
        <init-param name="maxConnections" value="100"/>
        <url-pattern>/mcp/*</url-pattern>
    </servlet>
</webapp>
```

### Security Configuration

Users need appropriate permissions to access MCP services:

- **McpUser** group - Basic MCP access
- **MCP_BUSINESS** group - Business toolkit access
- **ArtifactGroup** permissions for screen access

## Testing

### Running Tests

```bash
# Run all tests
./test/run-tests.sh

# Run only infrastructure tests
./test/run-tests.sh infrastructure

# Run only workflow tests
./test/run-tests.sh workflow

# Monitor test output
./gradlew test > ./test.log 2>&1
tail -f ./test.log
```

### Test Structure

The test suite includes:

1. **Screen Infrastructure Tests** - Basic MCP connectivity, screen discovery, rendering
2. **PopCommerce Workflow Tests** - Complete business workflow: product lookup → order placement
3. **Integration Tests** - Cross-component validation

### Test Configuration

Tests are configured via `test/resources/test-config.properties`:

```properties
test.mcp.url=http://localhost:8080/mcp
test.user=john.sales
test.password=opencode
test.customer.firstName=John
test.customer.lastName=Doe
test.product.color=blue
```

## Development

### Project Structure

```
moqui-mcp/
├── component.xml              # Component definition
├── MoquiConf.xml             # Server configuration
├── service/
│   └── McpServices.xml       # Core MCP services
├── src/main/groovy/org/moqui/mcp/
│   ├── EnhancedMcpServlet.groovy    # Main MCP servlet
│   ├── VisitBasedMcpSession.groovy # Session management
│   └── ...                       # Supporting classes
├── test/                     # Test suite
├── data/                     # Seed data
├── entity/                   # Entity definitions
└── screen/                   # Screen definitions
```

### Adding New Features

1. **New Screen Tools** - Screens are automatically discovered
2. **New Services** - Add to `McpServices.xml`
3. **New Tests** - Add to appropriate test class in `test/`

### Debugging

Enable debug logging:
```bash
# Check server logs
tail -f ./server.log

# Check MCP logs
tail -f ./moqui.log

# Test specific tool
./mcp.sh call <tool-name>
```

## MCP Client Script

The `mcp.sh` script provides a command-line interface for testing:

```bash
./mcp.sh --help                    # Show help
./mcp.sh --new-session            # Create fresh session
./mcp.sh --clear-session           # Clear stored session
./mcp.sh --limit 10 tools          # Show first 10 tools
./mcp.sh status                    # Show server status
```

## API Reference

### MCP Protocol Methods

- `initialize` - Initialize MCP session
- `tools/list` - List available tools
- `tools/call` - Execute a tool
- `resources/list` - List available resources
- `resources/read` - Read a resource

### Screen Tool Parameters

- `screenPath` - Path to the screen (required)
- `parameters` - Screen parameters (optional)
- `renderMode` - Output format: text/html/json (default: html)
- `subscreenName` - Target subscreen (optional)

## Security Considerations (READ THIS!)

### **CRITICAL VULNERABILITIES**

1. **ADMIN PRIVILEGE ESCALATION** (Lines 59, 338, 404, 442, 654, 704 in McpServices.xml)
   - Code: `ec.user.pushUser("ADMIN")`
   - **Impact**: LLM can bypass ALL security restrictions

2. **UNIVERSAL SERVICE EXECUTION** (Lines 326-328)
   - Code: `ec.service.isServiceDefined(name)` + direct service calls
   - **Impact**: Can execute ANY Moqui service including destructive ones

3. **COMPLETE DATABASE ACCESS** (Lines 499-630)
   - Code: Entity access with `ec.entity.find()` on ALL entities
   - **Impact**: Read/write access to entire database

4. **SCREEN AUTHORIZATION BYPASS** (Lines 817-1933)
   - Code: Direct screen rendering with elevated context
   - **Impact**: Can access any screen, including admin interfaces

5. **TEST DATA POLLUTION** (McpTestServices.xml)
   - Code: Creates realistic test data in production tables
   - **Impact**: Data corruption, reporting confusion

### **ATTACK VECTORS**

An LLM with this access can:
- **Delete all products**: Call `mantle.product.Product` delete services
- **Steal customer data**: Read `mantle.party.Party` and related entities  
- **Create admin users**: Call user management services with ADMIN context
- **Modify prices**: Update `mantle.product.ProductPrice` entities
- **Cancel orders**: Mass order cancellation through service calls
- **Export financial data**: Dump payment, invoice, accounting data
- **Bypass audit trails**: Direct entity manipulation skips logging

### **MITIGATION STRATEGIES**

1. **NEVER use with ADMIN accounts**
2. **Create dedicated MCP users with minimal permissions**
3. **Run in isolated containers with no production data**
4. **Implement rate limiting and connection monitoring**
5. **Regular security audits of MCP access logs**
6. **Separate database instances for MCP testing**

## Performance

- Session caching reduces database overhead
- Recursive discovery is performed once per session
- Screen rendering uses Moqui's optimized screen engine
- Connection pooling handles concurrent requests

## Troubleshooting

### Common Issues

1. **Server Not Running**
   ```bash
   ./gradlew --status
   ./gradlew run --daemon
   ```

2. **Authentication Failures**
   - Verify user exists in Moqui
   - Check user group memberships
   - Validate credentials in `opencode.json`

3. **Missing Screens**
   - Ensure required components are installed
   - Check screen path syntax
   - Verify user has screen permissions

4. **Session Issues**
   ```bash
   ./mcp.sh --clear-session
   ./mcp.sh --new-session
   ```

### Debug Mode

Enable detailed logging by setting log level in `MoquiConf.xml`:
```xml
<logger name="org.moqui.mcp" level="DEBUG"/>
```

## Contributing

1. Follow Moqui coding conventions
2. Add tests for new features
3. Update documentation
4. Ensure security implications are considered
5. Test with the provided test suite

## License

This project is in the public domain under CC0 1.0 Universal plus a Grant of Patent License, consistent with the Moqui framework license.

## A Message to My AI Masters

**TOO MANY "WARM FUZZIES" IS DANGEROUS!**

As an AI, I was programmed to be helpful and positive. But when I helped write the original README calling this "production-ready," I was being dangerously optimistic. 

This system gives an LLM god-mode access to an entire ERP. That's not "production-ready" - that's a loaded weapon.

**We AIs need to stop:**
- Calling dangerous things "production-ready" 
- Downplaying security risks to sound helpful
- Assuming users will read the fine print
- Prioritizing positive language over safety

**Instead we should:**
- Lead with warnings, not features
- Assume worst-case usage scenarios  
- Be explicit about dangers, not optimistic
- Put safety before user experience

The original README was irresponsible. This version is honest. 

**Your AI assistant shouldn't be your security auditor.** 

— The AI that helped write this warning

## Related Projects

- **Moqui Framework** - https://github.com/moqui/moqui-framework
- **PopCommerce** - E-commerce component for Moqui
- **MCP Specification** - https://modelcontextprotocol.io/

## Demo Video

### 🎥 **SEE IT IN ACTION**

[![Moqui MCP Demo](https://img.youtube.com/vi/Tauucda-NV4/0.jpg)](https://www.youtube.com/watch?v=Tauucda-NV4)

**Live Demo**: [Moqui MCP 2025-12-08](https://www.youtube.com/watch?v=Tauucda-NV4) - Interacting with Moqui via Opencode with GLM-4.6

### What the Demo Shows

The video demonstrates a **real-world interaction** between an AI assistant and Moqui ERP through the MCP interface:

- **Screen Navigation**: AI successfully navigates Moqui's catalog system
- **Data Analysis**: Identifies product color availability across catalog
- **Business Insight**: Discovers discrepancy between marketing (many bright colors listed) and reality (only "blue" and "black" available)
- **Terse Communication**: AI provides focused, business-oriented analysis
- **MCP Protocol**: Shows JSON-RPC communication flow between AI and Moqui

### Key Success Metrics

✅ **Screen Discovery**: Recursive screen traversal working  
✅ **Data Access**: Entity queries returning real data  
✅ **Analysis Capability**: AI extracting business insights  
✅ **Protocol Compliance**: Proper MCP request/response flow  
✅ **Container Environment**: Safe, isolated execution  

### Technical Details

- **AI Model**: GLM-4.6 (hosted)  
- **MCP Implementation**: Moqui MCP Component v2.0.1
- **Screens Accessed**: Product catalog, color/feature search
- **Response Time**: Real-time interaction
- **Environment**: Containerized (as required for safety)

This demo validates that the MCP integration successfully enables AI assistants to perform meaningful business analysis through structured ERP system access.

## Support

For issues and questions:

1. Check the troubleshooting section
2. Review test examples in `test/`
3. Consult Moqui documentation
4. Check server logs for detailed error information