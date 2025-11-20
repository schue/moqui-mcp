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

import groovy.json.JsonOutput

/**
 * Simple JSON-RPC Message classes for MCP compatibility
 */
class JsonRpcMessage {
    String jsonrpc = "2.0"
    
    String toJson() {
        return JsonOutput.toJson(this)
    }
}

class JsonRpcResponse extends JsonRpcMessage {
    Object id
    Object result
    Map error
    
    JsonRpcResponse(Object result, Object id) {
        this.result = result
        this.id = id
    }
    
    JsonRpcResponse(Map error, Object id) {
        this.error = error
        this.id = id
    }
}

class JsonRpcNotification extends JsonRpcMessage {
    String method
    Object params
    
    JsonRpcNotification(String method, Object params = null) {
        this.method = method
        this.params = params
    }
}