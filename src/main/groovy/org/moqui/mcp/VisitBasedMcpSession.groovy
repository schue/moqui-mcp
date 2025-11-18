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

import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP Session implementation that integrates with Moqui's Visit system
 * Provides SDK-style session management while leveraging Moqui's built-in tracking
 */
class VisitBasedMcpSession implements MoquiMcpTransport {
    protected final static Logger logger = LoggerFactory.getLogger(VisitBasedMcpSession.class)
    
    private final String sessionId
    private final String visitId
    private final PrintWriter writer
    private final ExecutionContextImpl ec
    private final AtomicBoolean active = new AtomicBoolean(true)
    private final AtomicBoolean closing = new AtomicBoolean(false)
    private final AtomicLong messageCount = new AtomicLong(0)
    private final Date createdAt
    
    // MCP session metadata stored in Visit context
    private final Map<String, Object> sessionMetadata = new ConcurrentHashMap<>()
    
    VisitBasedMcpSession(String sessionId, String visitId, PrintWriter writer, ExecutionContextImpl ec) {
        this.sessionId = sessionId
        this.visitId = visitId
        this.writer = writer
        this.ec = ec
        this.createdAt = new Date()
        
        // Initialize session metadata in Visit context
        initializeSessionMetadata()
    }
    
    private void initializeSessionMetadata() {
        try {
            // Store MCP session info in Visit context for persistence
            if (visitId && ec) {
                def visit = ec.entity.find("moqui.server.Visit").condition("visitId", visitId).one()
                if (visit) {
                    // Store MCP session metadata as JSON in Visit's context or a separate field
                    sessionMetadata.put("mcpSessionId", sessionId)
                    sessionMetadata.put("mcpCreatedAt", createdAt.time)
                    sessionMetadata.put("mcpProtocolVersion", "2025-06-18")
                    sessionMetadata.put("mcpTransportType", "SSE")
                    
                    logger.info("MCP Session ${sessionId} initialized with Visit ${visitId}")
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to initialize session metadata for Visit ${visitId}: ${e.message}")
        }
    }
    
    @Override
    void sendMessage(McpSchema.JSONRPCMessage message) {
        if (!active.get() || closing.get()) {
            logger.warn("Attempted to send message on inactive or closing session ${sessionId}")
            return
        }
        
        try {
            String jsonMessage = message.toJson()
            sendSseEvent("message", jsonMessage)
            messageCount.incrementAndGet()
            
            // Update session activity in Visit
            updateSessionActivity()
            
        } catch (Exception e) {
            logger.error("Failed to send message on session ${sessionId}: ${e.message}")
            if (e.message?.contains("disconnected") || e.message?.contains("Client disconnected")) {
                close()
            }
        }
    }
    
    @Override
    void closeGracefully() {
        if (!active.compareAndSet(true, false)) {
            return // Already closed
        }
        
        closing.set(true)
        logger.info("Gracefully closing MCP session ${sessionId}")
        
        try {
            // Send graceful shutdown notification
            def shutdownMessage = new McpSchema.JSONRPCMessage([
                type: "shutdown",
                sessionId: sessionId,
                timestamp: System.currentTimeMillis()
            ], null)
            sendMessage(shutdownMessage)
            
            // Give some time for message to be sent
            Thread.sleep(100)
            
        } catch (Exception e) {
            logger.warn("Error during graceful shutdown of session ${sessionId}: ${e.message}")
        } finally {
            close()
        }
    }
    
    @Override
    void close() {
        if (!active.compareAndSet(true, false)) {
            return // Already closed
        }
        
        logger.info("Closing MCP session ${sessionId} (messages sent: ${messageCount.get()})")
        
        try {
            // Update Visit with session end info
            updateSessionEnd()
            
            // Send final close event if writer is still available
            if (writer && !writer.checkError()) {
                sendSseEvent("close", groovy.json.JsonOutput.toJson([
                    type: "disconnected",
                    sessionId: sessionId,
                    messageCount: messageCount.get(),
                    timestamp: System.currentTimeMillis()
                ]))
            }
            
        } catch (Exception e) {
            logger.warn("Error during session close ${sessionId}: ${e.message}")
        }
    }
    
    @Override
    boolean isActive() {
        return active.get() && !closing.get() && writer && !writer.checkError()
    }
    
    @Override
    String getSessionId() {
        return sessionId
    }
    
    @Override
    String getVisitId() {
        return visitId
    }
    
    /**
     * Get session statistics
     */
    Map getSessionStats() {
        return [
            sessionId: sessionId,
            visitId: visitId,
            createdAt: createdAt,
            messageCount: messageCount.get(),
            active: active.get(),
            closing: closing.get(),
            duration: System.currentTimeMillis() - createdAt.time
        ]
    }
    
    /**
     * Send SSE event with proper formatting
     */
    private void sendSseEvent(String eventType, String data) throws IOException {
        if (!writer || writer.checkError()) {
            throw new IOException("Writer is closed or client disconnected")
        }
        
        writer.write("event: " + eventType + "\n")
        writer.write("data: " + data + "\n\n")
        writer.flush()
        
        if (writer.checkError()) {
            throw new IOException("Client disconnected during write")
        }
    }
    
    /**
     * Update session activity in Visit record
     */
    private void updateSessionActivity() {
        try {
            if (visitId && ec) {
                // Update Visit with latest activity
                ec.service.sync().name("update", "moqui.server.Visit")
                    .parameters([
                        visitId: visitId,
                        thruDate: ec.user.getNowTimestamp()
                    ])
                    .call()
                
                // Could also update a custom field for MCP-specific activity
                sessionMetadata.put("mcpLastActivity", System.currentTimeMillis())
                sessionMetadata.put("mcpMessageCount", messageCount.get())
            }
        } catch (Exception e) {
            logger.debug("Failed to update session activity: ${e.message}")
        }
    }
    
    /**
     * Update Visit record with session end information
     */
    private void updateSessionEnd() {
        try {
            if (visitId && ec) {
                // Update Visit with session end info
                ec.service.sync().name("update", "moqui.server.Visit")
                    .parameters([
                        visitId: visitId,
                        thruDate: ec.user.getNowTimestamp()
                    ])
                    .call()
                
                // Store final session metadata
                sessionMetadata.put("mcpEndedAt", System.currentTimeMillis())
                sessionMetadata.put("mcpFinalMessageCount", messageCount.get())
                
                logger.info("Updated Visit ${visitId} with MCP session end info")
            }
        } catch (Exception e) {
            logger.warn("Failed to update session end for Visit ${visitId}: ${e.message}")
        }
    }
    
    /**
     * Get session metadata
     */
    Map getSessionMetadata() {
        return new HashMap<>(sessionMetadata)
    }
    
    /**
     * Add custom metadata to session
     */
    void addSessionMetadata(String key, Object value) {
        sessionMetadata.put(key, value)
    }
}