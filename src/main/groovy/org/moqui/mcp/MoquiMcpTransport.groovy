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
package org.moqui.mcp

import groovy.json.JsonBuilder

/**
 * MCP Transport interface compatible with Servlet 4.0 and Moqui Visit system
 * Provides SDK-style session management capabilities while maintaining compatibility
 */
interface MoquiMcpTransport {
    /**
     * Send a JSON-RPC message through this transport
     * @param message The MCP JSON-RPC message to send
     */
    void sendMessage(McpSchema.JSONRPCMessage message)
    
    /**
     * Close the transport gracefully, allowing in-flight messages to complete
     */
    void closeGracefully()
    
    /**
     * Force close the transport immediately
     */
    void close()
    
    /**
     * Check if the transport is still active
     * @return true if transport is active, false otherwise
     */
    boolean isActive()
    
    /**
     * Get the session ID associated with this transport
     * @return the MCP session ID
     */
    String getSessionId()
    
    /**
     * Get the associated Moqui Visit ID
     * @return the Visit ID if available, null otherwise
     */
    String getVisitId()
}

/**
 * Simple implementation of MCP JSON-RPC message schema
 * Compatible with MCP protocol specifications
 */
class McpSchema {
    static class JSONRPCMessage {
        String jsonrpc = "2.0"
        Object id
        String method
        Map params
        Object result
        Map error
        
        JSONRPCMessage(String method, Map params = null, Object id = null) {
            this.method = method
            this.params = params
            this.id = id
        }
        
        JSONRPCMessage(Object result, Object id) {
            this.result = result
            this.id = id
        }
        
        JSONRPCMessage(Map error, Object id) {
            this.error = error
            this.id = id
        }
        
        String toJson() {
            return new JsonBuilder(this).toString()
        }
        
        static JSONRPCMessage fromJson(String json) {
            // Simple JSON parsing - in production would use proper JSON parser
            def slurper = new groovy.json.JsonSlurper()
            def data = slurper.parseText(json)
            
            if (data.error) {
                return new JSONRPCMessage(data.error, data.id)
            } else if (data.result != null) {
                return new JSONRPCMessage(data.result, data.id)
            } else {
                return new JSONRPCMessage(data.method, data.params, data.id)
            }
        }
    }
}