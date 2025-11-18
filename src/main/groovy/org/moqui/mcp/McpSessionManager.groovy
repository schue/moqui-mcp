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

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP Session Manager with SDK-style capabilities
 * Provides centralized session management, broadcasting, and graceful shutdown
 */
class McpSessionManager {
    protected final static Logger logger = LoggerFactory.getLogger(McpSessionManager.class)
    
    private final Map<String, VisitBasedMcpSession> sessions = new ConcurrentHashMap<>()
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false)
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2)
    
    // Session cleanup and monitoring
    private final long sessionTimeoutMs = 30 * 60 * 1000 // 30 minutes
    private final long cleanupIntervalMs = 5 * 60 * 1000 // 5 minutes
    
    McpSessionManager() {
        // Start periodic cleanup task
        scheduler.scheduleAtFixedRate(this::cleanupInactiveSessions, 
            cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS)
        
        logger.info("MCP Session Manager initialized")
    }
    
    /**
     * Register a new session
     */
    void registerSession(VisitBasedMcpSession session) {
        if (isShuttingDown.get()) {
            logger.warn("Rejecting session registration during shutdown: ${session.sessionId}")
            return
        }
        
        sessions.put(session.sessionId, session)
        logger.info("Registered MCP session ${session.sessionId} (total: ${sessions.size()})")
        
        // Send welcome message to new session
        def welcomeMessage = new McpSchema.JSONRPCMessage([
            type: "welcome",
            sessionId: session.sessionId,
            totalSessions: sessions.size(),
            timestamp: System.currentTimeMillis()
        ], null)
        session.sendMessage(welcomeMessage)
    }
    
    /**
     * Unregister a session
     */
    void unregisterSession(String sessionId) {
        def session = sessions.remove(sessionId)
        if (session) {
            logger.info("Unregistered MCP session ${sessionId} (remaining: ${sessions.size()})")
        }
    }
    
    /**
     * Get session by ID
     */
    VisitBasedMcpSession getSession(String sessionId) {
        return sessions.get(sessionId)
    }
    
    /**
     * Broadcast message to all active sessions
     */
    void broadcast(McpSchema.JSONRPCMessage message) {
        if (isShuttingDown.get()) {
            logger.warn("Rejecting broadcast during shutdown")
            return
        }
        
        def inactiveSessions = []
        def activeCount = 0
        
        sessions.values().each { session ->
            try {
                if (session.isActive()) {
                    session.sendMessage(message)
                    activeCount++
                } else {
                    inactiveSessions << session.sessionId
                }
            } catch (Exception e) {
                logger.warn("Error broadcasting to session ${session.sessionId}: ${e.message}")
                inactiveSessions << session.sessionId
            }
        }
        
        // Clean up inactive sessions
        inactiveSessions.each { sessionId ->
            unregisterSession(sessionId)
        }
        
        logger.info("Broadcast message to ${activeCount} active sessions (removed ${inactiveSessions.size()} inactive)")
    }
    
    /**
     * Send message to specific session
     */
    boolean sendToSession(String sessionId, McpSchema.JSONRPCMessage message) {
        def session = sessions.get(sessionId)
        if (!session) {
            return false
        }
        
        try {
            if (session.isActive()) {
                session.sendMessage(message)
                return true
            } else {
                unregisterSession(sessionId)
                return false
            }
        } catch (Exception e) {
            logger.warn("Error sending to session ${sessionId}: ${e.message}")
            unregisterSession(sessionId)
            return false
        }
    }
    
    /**
     * Get session statistics
     */
    Map getSessionStatistics() {
        def stats = [
            totalSessions: sessions.size(),
            activeSessions: 0,
            closingSessions: 0,
            isShuttingDown: isShuttingDown.get(),
            uptime: System.currentTimeMillis() - (this.@startTime ?: System.currentTimeMillis()),
            sessions: []
        ]
        
        sessions.values().each { session ->
            def sessionStats = session.getSessionStats()
            stats.sessions << sessionStats
            
            if (sessionStats.active) {
                stats.activeSessions++
            }
            if (sessionStats.closing) {
                stats.closingSessions++
            }
        }
        
        return stats
    }
    
    /**
     * Initiate graceful shutdown
     */
    void shutdownGracefully() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            return // Already shutting down
        }
        
        logger.info("Initiating graceful MCP session manager shutdown")
        
        // Send shutdown notification to all sessions
        def shutdownMessage = new McpSchema.JSONRPCMessage([
            type: "server_shutdown",
            message: "Server is shutting down gracefully",
            timestamp: System.currentTimeMillis()
        ], null)
        broadcast(shutdownMessage)
        
        // Give sessions time to receive shutdown message
        scheduler.schedule({
            forceShutdown()
        }, 5, TimeUnit.SECONDS)
    }
    
    /**
     * Force immediate shutdown
     */
    void forceShutdown() {
        logger.info("Force shutting down MCP session manager")
        
        // Close all sessions
        sessions.values().each { session ->
            try {
                session.close()
            } catch (Exception e) {
                logger.warn("Error closing session ${session.sessionId}: ${e.message}")
            }
        }
        sessions.clear()
        
        // Shutdown scheduler
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        logger.info("MCP session manager shutdown complete")
    }
    
    /**
     * Clean up inactive sessions
     */
    private void cleanupInactiveSessions() {
        if (isShuttingDown.get()) {
            return
        }
        
        def now = System.currentTimeMillis()
        def inactiveSessions = []
        
        sessions.values().each { session ->
            def sessionStats = session.getSessionStats()
            def inactiveTime = now - (sessionStats.lastActivity ?: sessionStats.createdAt.time)
            
            if (!session.isActive() || inactiveTime > sessionTimeoutMs) {
                inactiveSessions << session.sessionId
            }
        }
        
        inactiveSessions.each { sessionId ->
            def session = sessions.get(sessionId)
            if (session) {
                try {
                    session.closeGracefully()
                } catch (Exception e) {
                    logger.warn("Error during cleanup of session ${sessionId}: ${e.message}")
                }
                unregisterSession(sessionId)
            }
        }
        
        if (inactiveSessions.size() > 0) {
            logger.info("Cleaned up ${inactiveSessions.size()} inactive MCP sessions")
        }
    }
    
    /**
     * Get active session count
     */
    int getActiveSessionCount() {
        return (int) sessions.values().count { it.isActive() }
    }
    
    /**
     * Check if manager is shutting down
     */
    boolean isShuttingDown() {
        return isShuttingDown.get()
    }
}