# Moqui MCP (Model Context Protocol) Implementation

A production-ready MCP interface for Moqui ERP that enables AI assistants to interact with Moqui business screens and services through recursive screen discovery.

## Overview

This implementation provides a bridge between AI assistants and Moqui ERP by exposing Moqui screens as MCP tools. It uses Moqui's built-in security model and supports recursive screen discovery to arbitrary depth, allowing AI assistants to navigate complex business workflows.

## Key Features

✅ **Recursive Screen Discovery** - Automatically discovers screens to arbitrary depth (e.g., Catalog → Product → FindProduct)

✅ **Security Model Preserved** - All subscreen access goes through parent screens, maintaining Moqui's security

✅ **Cross-Component Support** - Handles subscreens that reference different components (e.g., PopCommerce → SimpleScreens)

✅ **Robust Tool Naming** - Uses dot notation for first-level subscreens (`Catalog.Product`) and underscores for deeper levels (`Catalog.Product_FindProduct`)

✅ **Accurate Tool Execution** - Uses stored actual screen paths instead of deriving from tool names

✅ **Session Management** - Visit-based session management with proper authentication

✅ **Comprehensive Testing** - Java-based test suite with deterministic workflows

## Architecture

The implementation consists of:

- **EnhancedMcpServlet** - Main MCP servlet handling JSON-RPC 2.0 protocol
- **McpServices** - Core services for initialization, tool discovery, and execution
- **Screen Discovery** - Recursive screen traversal with XML parsing
- **Security Integration** - Moqui artifact authorization system
- **Test Suite** - Comprehensive Java/Groovy tests

## Quick Start

### Prerequisites

- Java 17+
- Moqui Framework
- Gradle
- PopCommerce component (for examples)

### Installation

1. Clone the repository:
```bash
git clone <repository-url>
cd moqui-mcp-2
```

2. Build the component:
```bash
./gradlew build
```

3. Start the Moqui server:
```bash
./gradlew run --daemon > ./server.log 2>&1 &
```

4. Verify MCP server is running:
```bash
./mcp.sh ping
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
moqui-mcp-2/
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

## Security Considerations

- All MCP requests go through Moqui's authentication system
- Screen access respects Moqui's artifact authorization
- Session management uses Moqui's Visit entity
- Tool execution is logged for audit purposes

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

## Related Projects

- **Moqui Framework** - https://github.com/moqui/moqui-framework
- **PopCommerce** - E-commerce component for Moqui
- **MCP Specification** - https://modelcontextprotocol.io/

## Support

For issues and questions:

1. Check the troubleshooting section
2. Review test examples in `test/`
3. Consult Moqui documentation
4. Check server logs for detailed error information