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
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP Session implementation that uses Moqui's Visit entity directly
 * Eliminates custom session management by leveraging Moqui's built-in Visit system
 */
class VisitBasedMcpSession implements MoquiMcpTransport {
    protected final static Logger logger = LoggerFactory.getLogger(VisitBasedMcpSession.class)
    
    private final EntityValue visit // The Visit entity record
    private final PrintWriter writer
    private final ExecutionContextImpl ec
    private final AtomicBoolean active = new AtomicBoolean(true)
    private final AtomicBoolean closing = new AtomicBoolean(false)
    private final AtomicLong messageCount = new AtomicLong(0)
    
    VisitBasedMcpSession(EntityValue visit, PrintWriter writer, ExecutionContextImpl ec) {
        this.visit = visit
        this.writer = writer
        this.ec = ec
        
        // Initialize MCP session in Visit if not already done
        initializeMcpSession()
    }
    
    private void initializeMcpSession() {
        try {
            def metadata = getSessionMetadata()
            if (!metadata.mcpSession) {
                // Mark this Visit as an MCP session
                metadata.mcpSession = true
                metadata.mcpProtocolVersion = "2025-11-25"
                metadata.mcpCreatedAt = System.currentTimeMillis()
                metadata.mcpTransportType = "SSE"
                metadata.mcpMessageCount = 0
                saveSessionMetadata(metadata)
                
                logger.info("MCP Session initialized for Visit ${visit.visitId}")
            }
        } catch (Exception e) {
            logger.warn("Failed to initialize MCP session for Visit ${visit.visitId}: ${e.message}")
        }
    }
    
    @Override
    void sendMessage(JsonRpcMessage message) {
        if (!active.get() || closing.get()) {
            logger.warn("Attempted to send message on inactive or closing session ${visit.visitId}")
            return
        }
        
        try {
            String jsonMessage = message.toJson()
            sendSseEvent("message", jsonMessage)
            messageCount.incrementAndGet()
            
            // Update session activity in Visit
            updateSessionActivity()
            
        } catch (Exception e) {
            logger.error("Failed to send message on session ${visit.visitId}: ${e.message}")
            if (e.message?.contains("disconnected") || e.message?.contains("Client disconnected")) {
                close()
            }
        }
    }
    
    void closeGracefully() {
        if (!active.compareAndSet(true, false)) {
            return // Already closed
        }
        
        closing.set(true)
        logger.info("Gracefully closing MCP session ${visit.visitId}")
        
        try {
            // Send graceful shutdown notification
            def shutdownMessage = new JsonRpcNotification("shutdown", [
                sessionId: visit.visitId,
                timestamp: System.currentTimeMillis()
            ])
            sendMessage(shutdownMessage)
            
            // Give some time for message to be sent
            Thread.sleep(100)
            
        } catch (Exception e) {
            logger.warn("Error during graceful shutdown of session ${visit.visitId}: ${e.message}")
        } finally {
            close()
        }
    }
    
    void close() {
        if (!active.compareAndSet(true, false)) {
            return // Already closed
        }
        
        logger.info("Closing MCP session ${visit.visitId} (messages sent: ${messageCount.get()})")
        
        try {
            // Update Visit with session end info
            updateSessionEnd()
            
            // Send final close event if writer is still available
            if (writer && !writer.checkError()) {
                sendSseEvent("close", groovy.json.JsonOutput.toJson([
                    type: "disconnected",
                    sessionId: visit.visitId,
                    messageCount: messageCount.get(),
                    timestamp: System.currentTimeMillis()
                ]))
            }
            
        } catch (Exception e) {
            logger.warn("Error during session close ${visit.visitId}: ${e.message}")
        }
    }
    
    @Override
    boolean isActive() {
        return active.get() && !closing.get() && writer && !writer.checkError()
    }
    
    @Override
    String getSessionId() {
        return visit.visitId
    }
    
    String getVisitId() {
        return visit.visitId
    }
    
    EntityValue getVisit() {
        return visit
    }
    
    /**
     * Get session statistics
     */
    Map getSessionStats() {
        return [
            sessionId: visit.visitId,
            visitId: visit.visitId,
            createdAt: visit.fromDate,
            messageCount: messageCount.get(),
            active: active.get(),
            closing: closing.get(),
            duration: System.currentTimeMillis() - visit.fromDate.time
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
            if (visit && ec) {
                // Update Visit with latest activity
                visit.thruDate = ec.user.getNowTimestamp()
                visit.update()
                
                // Update MCP-specific activity in metadata
                def metadata = getSessionMetadata()
                metadata.mcpLastActivity = System.currentTimeMillis()
                metadata.mcpMessageCount = messageCount.get()
                saveSessionMetadata(metadata)
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
            if (visit && ec) {
                // Update Visit with session end info
                visit.thruDate = ec.user.getNowTimestamp()
                visit.update()
                
                // Store final session metadata
                def metadata = getSessionMetadata()
                metadata.mcpEndedAt = System.currentTimeMillis()
                metadata.mcpFinalMessageCount = messageCount.get()
                saveSessionMetadata(metadata)
                
                logger.info("Updated Visit ${visit.visitId} with MCP session end info")
            }
        } catch (Exception e) {
            logger.warn("Failed to update session end for Visit ${visit.visitId}: ${e.message}")
        }
    }
    
    /**
     * Get session metadata from Visit's initialRequest field
     */
    Map getSessionMetadata() {
        try {
            def metadataJson = visit.initialRequest
            if (metadataJson) {
                return groovy.json.JsonSlurper().parseText(metadataJson) as Map
            }
        } catch (Exception e) {
            logger.debug("Failed to parse session metadata: ${e.message}")
        }
        return [:]
    }
    
    /**
     * Add custom metadata to session
     */
    void addSessionMetadata(String key, Object value) {
        def metadata = getSessionMetadata()
        metadata[key] = value
        saveSessionMetadata(metadata)
    }
    
    /**
     * Save session metadata to Visit's initialRequest field
     */
    private void saveSessionMetadata(Map metadata) {
        try {
            visit.initialRequest = groovy.json.JsonOutput.toJson(metadata)
            visit.update()
        } catch (Exception e) {
            logger.debug("Failed to save session metadata: ${e.message}")
        }
    }
}