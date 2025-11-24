# MCP Test Suite

This directory contains comprehensive tests for the Moqui MCP (Model Context Protocol) interface.

## Overview

The test suite validates the complete MCP functionality including:
- Basic MCP protocol operations
- Screen discovery and execution
- Service invocation through MCP
- Complete e-commerce workflows (product discovery → order placement)
- Session management and security
- Error handling and edge cases

## Test Structure

```
test/
├── client/                    # MCP client implementations
│   └── McpTestClient.groovy   # General-purpose MCP test client
├── workflows/                 # Workflow-specific tests
│   └── EcommerceWorkflowTest.groovy  # Complete e-commerce workflow test
├── integration/               # Integration tests (future)
├── run-tests.sh              # Main test runner script
└── README.md                 # This file
```

## Test Services

The test suite includes specialized MCP services in `../service/McpTestServices.xml`:

### Core Test Services
- `org.moqui.mcp.McpTestServices.create#TestProduct` - Create test products
- `org.moqui.mcp.McpTestServices.create#TestCustomer` - Create test customers
- `org.moqui.mcp.McpTestServices.create#TestOrder` - Create test orders
- `org.moqui.mcp.McpTestServices.get#TestProducts` - Retrieve test products
- `org.moqui.mcp.McpTestServices.get#TestOrders` - Retrieve test orders

### Workflow Services
- `org.moqui.mcp.McpTestServices.run#EcommerceWorkflow` - Complete e-commerce workflow
- `org.moqui.mcp.McpTestServices.cleanup#TestData` - Cleanup test data

## Running Tests

### Prerequisites

1. **Start MCP Server**:
   ```bash
   cd moqui-mcp-2
   ../gradlew run --daemon > ../server.log 2>&1 &
   ```

2. **Verify Server is Running**:
   ```bash
   curl -s -u "john.sales:opencode" "http://localhost:8080/mcp"
   ```

### Run All Tests

```bash
cd moqui-mcp-2
./test/run-tests.sh
```

### Run Individual Tests

#### General MCP Test Client
```bash
cd moqui-mcp-2
groovy -cp "lib/*:build/libs/*:../framework/build/libs/*:../runtime/lib/*" \
    test/client/McpTestClient.groovy
```

#### E-commerce Workflow Test
```bash
cd moqui-mcp-2
groovy -cp "lib/*:build/libs/*:../framework/build/libs/*:../runtime/lib/*" \
    test/workflows/EcommerceWorkflowTest.groovy
```

## Test Workflows

### 1. Basic MCP Test Client (`McpTestClient.groovy`)

Tests core MCP functionality:
- ✅ Session initialization and management
- ✅ Tool discovery and execution
- ✅ Resource access and querying
- ✅ Error handling and validation

**Workflows**:
- Product Discovery Workflow
- Order Placement Workflow
- E-commerce Full Workflow

### 2. E-commerce Workflow Test (`EcommerceWorkflowTest.groovy`)

Tests complete business workflow:
- ✅ Product Discovery
- ✅ Customer Management
- ✅ Order Placement
- ✅ Screen-based Operations
- ✅ Complete Workflow Execution
- ✅ Test Data Cleanup

## Test Data Management

### Automatic Cleanup
Test data is automatically created and cleaned up during tests:
- Products: Prefix `TEST-`
- Customers: Prefix `TEST-`
- Orders: Prefix `TEST-ORD-`

### Manual Cleanup
```bash
# Using mcp.sh
./mcp.sh call org.moqui.mcp.McpTestServices.cleanup#TestData olderThanHours=24

# Direct service call
curl -u "john.sales:opencode" -X POST \
  "http://localhost:8080/rest/s1/org/moqui/mcp/McpTestServices/cleanup#TestData" \
  -H "Content-Type: application/json" \
  -d '{"olderThanHours": 24}'
```

## Expected Test Results

### Successful Test Output
```
🧪 E-commerce Workflow Test for MCP
==================================
🚀 Initializing MCP session for workflow test...
✅ Session initialized: 123456

🔍 Step 1: Product Discovery
===========================
Found 44 available tools
Found 8 product-related tools
✅ Created test product: TEST-1700123456789

👥 Step 2: Customer Management
===============================
✅ Created test customer: TEST-1700123456790

🛒 Step 3: Order Placement
==========================
✅ Created test order: TEST-ORD-1700123456791

🖥️ Step 4: Screen-based Workflow
=================================
Found 2 catalog screens
✅ Successfully executed catalog screen: PopCommerceAdmin/Catalog

🔄 Step 5: Complete E-commerce Workflow
========================================
✅ Complete workflow executed successfully
   Workflow ID: WF-1700123456792
   Product ID: TEST-1700123456793
   Customer ID: TEST-1700123456794
   Order ID: TEST-ORD-1700123456795
   ✅ Create Product: Test product created successfully
   ✅ Create Customer: Test customer created successfully
   ✅ Create Order: Test order created successfully

🧹 Step 6: Cleanup Test Data
============================
✅ Test data cleanup completed
   Deleted orders: 3
   Deleted products: 3
   Deleted customers: 2

============================================================
📋 E-COMMERCE WORKFLOW TEST REPORT
============================================================
Duration: 2847ms

✅ productDiscovery
✅ customerManagement
✅ orderPlacement
✅ screenBasedWorkflow
✅ completeWorkflow
✅ cleanup

Overall Result: 6/6 steps passed
Success Rate: 100%
🎉 ALL TESTS PASSED! MCP e-commerce workflow is working correctly.
============================================================
```

## Troubleshooting

### Common Issues

#### 1. MCP Server Not Running
```
❌ MCP server is not running at http://localhost:8080/mcp
```
**Solution**: Start the server first
```bash
cd moqui-mcp-2 && ../gradlew run --daemon > ../server.log 2>&1 &
```

#### 2. Authentication Failures
```
❌ Error: Authentication required
```
**Solution**: Verify credentials in `opencode.json` or use default `john.sales:opencode`

#### 3. Missing Test Services
```
❌ Error: Service not found: org.moqui.mcp.McpTestServices.create#TestProduct
```
**Solution**: Rebuild the project
```bash
cd moqui-mcp-2 && ../gradlew build
```

#### 4. Classpath Issues
```
❌ Error: Could not find class McpTestClient
```
**Solution**: Ensure proper classpath
```bash
groovy -cp "lib/*:build/libs/*:../framework/build/libs/*:../runtime/lib/*" ...
```

### Debug Mode

Enable verbose output in tests:
```bash
# For mcp.sh
./mcp.sh --verbose ping

# For Groovy tests
# Add debug prints in the test code
```

### Log Analysis

Check server logs for detailed error information:
```bash
tail -f ../server.log
tail -f ../moqui.log
```

## Extending Tests

### Adding New Test Services

1. Create service in `../service/McpTestServices.xml`
2. Rebuild: `../gradlew build`
3. Add test method in appropriate test client
4. Update documentation

### Adding New Workflows

1. Create new test class in `test/workflows/`
2. Extend base test functionality
3. Add to test runner if needed
4. Update documentation

## Performance Testing

### Load Testing
```bash
# Run multiple concurrent tests
for i in {1..10}; do
    groovy test/workflows/EcommerceWorkflowTest.groovy &
done
wait
```

### Benchmarking
Tests track execution time and can be used for performance benchmarking.

## Security Testing

The test suite validates:
- ✅ Authentication requirements
- ✅ Authorization enforcement
- ✅ Session isolation
- ✅ Permission-based access control

## Integration with CI/CD

### GitHub Actions Example
```yaml
- name: Run MCP Tests
  run: |
    cd moqui-mcp-2
    ./test/run-tests.sh
```

### Jenkins Pipeline
```groovy
stage('MCP Tests') {
    steps {
        sh 'cd moqui-mcp-2 && ./test/run-tests.sh'
    }
}
```

## Contributing

When adding new tests:
1. Follow existing naming conventions
2. Include proper error handling
3. Add comprehensive logging
4. Update documentation
5. Test with different data scenarios

## Support

For test-related issues:
1. Check server logs
2. Verify MCP server status
3. Validate test data
4. Review authentication setup
5. Check network connectivity

---

**Note**: These tests are designed for development and testing environments. Use appropriate test data and cleanup procedures in production environments.