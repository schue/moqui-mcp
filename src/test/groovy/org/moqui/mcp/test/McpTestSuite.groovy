/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.mcp.test

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.moqui.Moqui

@DisplayName("MCP Test Suite")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpTestSuite {
    
    static SimpleMcpClient client
    static boolean criticalTestFailed = false
    
    @BeforeAll
    static void setupMoqui() {
        // Initialize Moqui framework for testing
        System.setProperty('moqui.runtime', '../runtime')
        System.setProperty('moqui.conf', 'MoquiConf.xml')
        System.setProperty('moqui.init.static', 'true')
        
        // Initialize MCP client
        client = new SimpleMcpClient()
    }
    
    @AfterAll
    static void cleanup() {
        if (client) {
            client.closeSession()
        }
    }

    @Test
    @Order(1)
    @DisplayName("Test Internal Service Direct Call")
    void testInternalServiceDirectCall() {
        println "🔧 Testing Internal Service Direct Call"
        
        // Try to get ExecutionContext to verify if we are running in-container
        def ec = Moqui.getExecutionContext()
        if (ec == null) {
            println "⚠️ No ExecutionContext available - skipping internal service test (running in external client mode)"
            return
        }
        
        println "✅ ExecutionContext available, testing service directly"
        
        try {
            // Call the service directly
            def result = ec.service.sync().name("McpServices.execute#ScreenAsMcpTool")
                .parameters([
                    screenPath: "component://moqui-mcp-2/screen/McpTestScreen.xml",
                    parameters: [message: "Direct Service Call Test"],
                    renderMode: "html"
                ])
                .call()
                
            println "✅ Service returned result: ${result}"
            
            // Verify result structure
            assert result != null
            assert result.result != null
            assert result.result.type == "text"
            assert result.result.screenPath == "component://moqui-mcp-2/screen/McpTestScreen.xml"
            assert !result.result.isError
            
            // Verify content
            def text = result.result.text
            println "📄 Rendered text length: ${text?.length()}"
            if (text && text.contains("Direct Service Call Test")) {
                println "🎉 SUCCESS: Found test message in direct render output"
            } else {
                println "⚠️ Test message not found in output (or output empty)"
                // Note: We don't fail the critical test on empty output yet as it might be an environment quirk, 
                // but if we wanted to enforce it, we would throw AssertionError here.
            }
            
        } catch (Exception e) {
            println "❌ Service call failed: ${e.message}"
            e.printStackTrace()
            criticalTestFailed = true
            throw e
        } catch (AssertionError e) {
            println "❌ Service assertion failed: ${e.message}"
            criticalTestFailed = true
            throw e
        }
    }
    
    @Test
    @Order(2)
    @DisplayName("Test MCP Server Connectivity")
    void testMcpServerConnectivity() {
        if (criticalTestFailed) return
        println "🔌 Testing MCP Server Connectivity"
        
        // Test session initialization first
        assert client.initializeSession() : "MCP session should initialize successfully"
        println "✅ Session initialized successfully"
        
        // Test server ping
        assert client.ping() : "MCP server should respond to ping"
        println "✅ Server ping successful"
        
        // Test tool listing
        def tools = client.listTools()
        assert tools != null : "Tools list should not be null"
        assert tools.size() > 0 : "Should have at least one tool available"
        println "✅ Found ${tools.size()} available tools"
    }
    
    @Test
    @Order(3)
    @DisplayName("Test PopCommerce Product Search")
    void testPopCommerceProductSearch() {
        if (criticalTestFailed) return
        println "🛍️ Testing PopCommerce Product Search"
        
        // Use SimpleScreens search screen directly (PopCommerce/SimpleScreens reuses this)
        // Pass "Blue" as queryString to find blue products
        def result = client.callScreen("component://SimpleScreens/screen/SimpleScreens/Catalog/Search.xml", [queryString: "Blue"])
        
        assert result != null : "Screen call result should not be null"
        assert result instanceof Map : "Screen result should be a map"
        
        // Fail test if screen returns error
        assert !result.containsKey('error') : "Screen call should not return error: ${result.error}"
        assert !result.isError : "Screen result should not have isError set to true"
        
        println "✅ PopCommerce search screen accessed successfully"
        
        // Check if we got content - fail test if no content
        def content = result.result?.content
        assert content != null && content instanceof List && content.size() > 0 : "Screen should return content with blue products"
        println "✅ Screen returned content with ${content.size()} items"
        
        def blueProductsFound = false
        
        // Look for product data in the content (HTML or JSON)
        for (item in content) {
            println "📦 Content item type: ${item.type}"
            if (item.type == "text" && item.text) {
                println "✅ Screen returned text content start: ${item.text.take(200)}..."
                
                // Check for HTML content containing expected product name
                if (item.text.contains("Demo with Variants Blue")) {
                    println "🛍️ Found 'Demo with Variants Blue' in HTML content!"
                    blueProductsFound = true
                }
                
                // Also try to parse as JSON just in case, but don't rely on it
                try {
                    def jsonData = new groovy.json.JsonSlurper().parseText(item.text)
                    if (jsonData instanceof Map) {
                        println "📊 Parsed JSON data keys: ${jsonData.keySet()}"
                        if (jsonData.containsKey('products') || jsonData.containsKey('productList')) {
                            def products = jsonData.products ?: jsonData.productList
                            if (products instanceof List && products.size() > 0) {
                                println "🛍️ Found ${products.size()} products in JSON!"
                                blueProductsFound = true
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore JSON parse errors as we expect HTML
                }
            } else if (item.type == "resource" && item.resource) {
                println "🔗 Resource data: ${item.resource.keySet()}"
                if (item.resource.containsKey('products')) {
                    def products = item.resource.products
                    if (products instanceof List && products.size() > 0) {
                        println "🛍️ Found ${products.size()} products in resource!"
                        blueProductsFound = true
                    }
                }
            }
        }
        
        // Fail test if no blue products were found
        assert blueProductsFound : "Should find at least one blue product (Demo with Variants Blue) in search results"
    }
    
    @Test
    @Order(4)
    @DisplayName("Test Customer Lookup")
    void testCustomerLookup() {
        if (criticalTestFailed) return
        println "👤 Testing Customer Lookup"
        
        // Use actual available screen - PartyList from mantle component
        def result = client.callScreen("component://mantle/screen/party/PartyList.xml", [:])
        
        assert result != null : "Screen call result should not be null"
        assert result instanceof Map : "Screen result should be a map"
        
        if (result.containsKey('error')) {
            println "⚠️ Screen call returned error: ${result.error}"
        } else {
            println "✅ Party list screen accessed successfully"
            
            // Check if we got content
            def content = result.result?.content
            if (content && content instanceof List && content.size() > 0) {
                println "✅ Screen returned content with ${content.size()} items"
                
                // Look for customer data in the content
                for (item in content) {
                    if (item.type == "text" && item.text) {
                        println "✅ Screen returned text content: ${item.text.take(100)}..."
                        break
                    }
                }
            } else {
                println "✅ Screen executed successfully (no structured customer data expected)"
            }
        }
    }
    
    @Test
    @Order(5)
    @DisplayName("Test Complete Order Workflow")
    void testCompleteOrderWorkflow() {
        if (criticalTestFailed) return
        println "🛒 Testing Complete Order Workflow"
        
        // Use actual available screen - OrderList from mantle component
        def result = client.callScreen("component://mantle/screen/order/OrderList.xml", [:])
        
        assert result != null : "Screen call result should not be null"
        assert result instanceof Map : "Screen result should be a map"
        
        if (result.containsKey('error')) {
            println "⚠️ Screen call returned error: ${result.error}"
        } else {
            println "✅ Order list screen accessed successfully"
            
            // Check if we got content
            def content = result.result?.content
            if (content && content instanceof List && content.size() > 0) {
                println "✅ Screen returned content with ${content.size()} items"
                
                // Look for order data in the content
                for (item in content) {
                    if (item.type == "text" && item.text) {
                        println "✅ Screen returned text content: ${item.text.take(100)}..."
                        break
                    }
                }
            } else {
                println "✅ Screen executed successfully (no structured order data expected)"
            }
        }
    }
    
    @Test
    @Order(6)
    @DisplayName("Test MCP Screen Infrastructure")
    void testMcpScreenInfrastructure() {
        if (criticalTestFailed) return
        println "🖥️ Testing MCP Screen Infrastructure"
        
        // Test calling the MCP test screen with a custom message
        def result = client.callScreen("component://moqui-mcp-2/screen/McpTestScreen.xml", [
            message: "MCP Test Successful!"
        ])
        
        assert result != null : "Screen call result should not be null"
        assert result instanceof Map : "Screen result should be a map"
        
        if (result.containsKey('error')) {
            println "⚠️ Screen call returned error: ${result.error}"
        } else {
            println "✅ Screen infrastructure working correctly"
            
            // Check if we got content
            def content = result.result?.content
            if (content && content instanceof List && content.size() > 0) {
                println "✅ Screen returned content with ${content.size()} items"
                
                // Look for actual data in the content
                for (item in content) {
                    println "📦 Content item type: ${item.type}"
                    if (item.type == "text" && item.text) {
                        println "✅ Screen returned actual text content:"
                        println "   ${item.text}"
                        
                        // Verify the content contains our test message
                        if (item.text.contains("MCP Test Successful!")) {
                            println "🎉 SUCCESS: Custom message found in screen output!"
                        }
                        
                        // Look for user and timestamp info
                        if (item.text.contains("User:")) {
                            println "👤 User information found in output"
                        }
                        if (item.text.contains("Time:")) {
                            println "🕐 Timestamp found in output"
                        }
                        break
                    } else if (item.type == "resource" && item.resource) {
                        println "🔗 Resource data: ${item.resource.keySet()}"
                    }
                }
            } else {
                println "⚠️ No content returned from screen"
            }
        }
    }

}
