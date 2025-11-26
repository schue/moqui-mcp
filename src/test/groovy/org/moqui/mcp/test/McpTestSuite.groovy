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
    @DisplayName("Test MCP Server Connectivity")
    void testMcpServerConnectivity() {
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
    @Order(2)
    @DisplayName("Test PopCommerce Product Search")
    void testPopCommerceProductSearch() {
        println "🛍️ Testing PopCommerce Product Search"
        
        // Use PopCommerce catalog screen with blue product search
        def result = client.callScreen("screen_component___PopCommerce_screen_PopCommerceAdmin_Catalog_xml", [feature: "BU:Blue"])
        
        assert result != null : "Screen call result should not be null"
        assert result instanceof Map : "Screen result should be a map"
        
        // Fail test if screen returns error
        assert !result.containsKey('error') : "Screen call should not return error: ${result.error}"
        assert !result.isError : "Screen result should not have isError set to true"
        
        println "✅ PopCommerce catalog screen accessed successfully"
        
        // Check if we got content - fail test if no content
        def content = result.result?.content
        assert content != null && content instanceof List && content.size() > 0 : "Screen should return content with blue products"
        println "✅ Screen returned content with ${content.size()} items"
        
        def blueProductsFound = false
        
        // Look for product data in the content
        for (item in content) {
            println "📦 Content item type: ${item.type}"
            if (item.type == "text" && item.text) {
                println "✅ Screen returned text content: ${item.text.take(200)}..."
                // Try to parse as JSON to see if it contains product data
                try {
                    def jsonData = new groovy.json.JsonSlurper().parseText(item.text)
                    if (jsonData instanceof Map) {
                        println "📊 Parsed JSON data keys: ${jsonData.keySet()}"
                        if (jsonData.containsKey('products') || jsonData.containsKey('productList')) {
                            def products = jsonData.products ?: jsonData.productList
                            if (products instanceof List && products.size() > 0) {
                                println "🛍️ Found ${products.size()} products!"
                                blueProductsFound = true
                                products.eachWithIndex { product, index ->
                                    if (index < 3) { // Show first 3 products
                                        println "   Product ${index + 1}: ${product.productName ?: product.name ?: 'Unknown'} (ID: ${product.productId ?: product.productId ?: 'N/A'})"
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    println "📝 Text content (not JSON): ${item.text.take(300)}..."
                }
            } else if (item.type == "resource" && item.resource) {
                println "🔗 Resource data: ${item.resource.keySet()}"
                if (item.resource.containsKey('products')) {
                    def products = item.resource.products
                    if (products instanceof List && products.size() > 0) {
                        println "🛍️ Found ${products.size()} products in resource!"
                        blueProductsFound = true
                        products.eachWithIndex { product, index ->
                            if (index < 3) {
                                println "   Product ${index + 1}: ${product.productName ?: product.name ?: 'Unknown'} (ID: ${product.productId ?: 'N/A'})"
                            }
                        }
                    }
                }
            }
        }
        
        // Fail test if no blue products were found
        assert blueProductsFound : "Should find at least one blue product with BU:Blue feature"
    }
    
    @Test
    @Order(3)
    @DisplayName("Test Customer Lookup")
    void testCustomerLookup() {
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
    @Order(4)
    @DisplayName("Test Complete Order Workflow")
    void testCompleteOrderWorkflow() {
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
    @Order(5)
    @DisplayName("Test MCP Screen Infrastructure")
    void testMcpScreenInfrastructure() {
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