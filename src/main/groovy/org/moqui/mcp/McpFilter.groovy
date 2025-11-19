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

import javax.servlet.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class McpFilter implements Filter {
    protected final static Logger logger = LoggerFactory.getLogger(McpFilter.class)
    
    private EnhancedMcpServlet mcpServlet = new EnhancedMcpServlet()
    
    @Override
    void init(FilterConfig filterConfig) throws ServletException {
        logger.info("========== MCP FILTER INITIALIZED ==========")
        // Initialize the servlet with filter config
        mcpServlet.init(new ServletConfig() {
            @Override
            String getServletName() { return "McpFilter" }
            @Override
            ServletContext getServletContext() { return filterConfig.getServletContext() }
            @Override
            String getInitParameter(String name) { return filterConfig.getInitParameter(name) }
            @Override
            Enumeration<String> getInitParameterNames() { return filterConfig.getInitParameterNames() }
        })
    }
    
    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        
        logger.info("========== MCP FILTER DOFILTER CALLED ==========")
        
        if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
            HttpServletRequest httpRequest = (HttpServletRequest) request
            HttpServletResponse httpResponse = (HttpServletResponse) response
            
            // Check if this is an MCP request
            String path = httpRequest.getRequestURI()
            logger.info("========== MCP FILTER PATH: {} ==========", path)
            
            if (path != null && path.contains("/mcpservlet")) {
                logger.info("========== MCP FILTER HANDLING REQUEST ==========")
                try {
                    // Handle MCP request directly, don't continue chain
                    mcpServlet.service(httpRequest, httpResponse)
                    return
                } catch (Exception e) {
                    logger.error("Error in MCP filter", e)
                    // Send error response directly
                    httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                    httpResponse.setContentType("application/json")
                    httpResponse.writer.write(groovy.json.JsonOutput.toJson([
                        jsonrpc: "2.0",
                        error: [code: -32603, message: "Internal error: " + e.message],
                        id: null
                    ]))
                    return
                }
            }
        }
        
        // Not an MCP request, continue chain
        chain.doFilter(request, response)
    }
    
    @Override
    void destroy() {
        mcpServlet.destroy()
        logger.info("McpFilter destroyed")
    }
}