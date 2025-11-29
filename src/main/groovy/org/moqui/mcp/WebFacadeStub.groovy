/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.mcp

import groovy.transform.CompileStatic
import org.moqui.context.*
import org.moqui.context.MessageFacade.MessageInfo
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ContextJavaUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.ServletContext
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpSession
import java.util.ArrayList
import java.util.EventListener

/** Stub implementation of WebFacade for testing/screen rendering without a real HTTP request */
@CompileStatic
class WebFacadeStub implements WebFacade {
    protected final static Logger logger = LoggerFactory.getLogger(WebFacadeStub.class)
    
    protected final ExecutionContextFactoryImpl ecfi
    protected final Map<String, Object> parameters
    protected final Map<String, Object> sessionAttributes
    protected final String requestMethod
    protected final String screenPath
    
    protected HttpServletRequest httpServletRequest
    protected HttpServletResponse httpServletResponse
    protected HttpSession httpSession
    
    protected Map<String, Object> requestAttributes = [:]
    protected Map<String, Object> applicationAttributes = [:]
    protected Map<String, Object> errorParameters = [:]
    
    protected List<MessageInfo> savedMessages = []
    protected List<MessageInfo> savedPublicMessages = []
    protected List<String> savedErrors = []
    protected List<ValidationError> savedValidationErrors = []
    
    protected List<Map> screenHistory = []
    
    protected String responseText = null
    protected Object responseJsonObj = null
    boolean skipJsonSerialize = false
    
    WebFacadeStub(ExecutionContextFactoryImpl ecfi, Map<String, Object> parameters, 
                   Map<String, Object> sessionAttributes, String requestMethod, String screenPath = null) {
        this.ecfi = ecfi
        this.parameters = parameters ?: [:]
        this.sessionAttributes = sessionAttributes ?: [:]
        this.requestMethod = requestMethod ?: "GET"
        this.screenPath = screenPath
        
        // Create mock HTTP objects
        createMockHttpObjects()
    }
    
    protected void createMockHttpObjects() {
        // Create mock HttpSession first
        this.httpSession = new MockHttpSession(this.sessionAttributes)
        
        // Create mock HttpServletRequest with session and screen path
        this.httpServletRequest = new MockHttpServletRequest(this.parameters, this.requestMethod, this.httpSession, this.screenPath)
        
        // Create mock HttpServletResponse with String output capture
        this.httpServletResponse = new MockHttpServletResponse()
        
        // Note: Objects are linked through the mock implementations
    }
    
    @Override
    String getRequestUrl() {
        if (logger.isDebugEnabled()) {
            logger.debug("WebFacadeStub.getRequestUrl() called - screenPath: ${screenPath}")
        }
        // Build URL based on actual screen path
        def path = screenPath ? "/${screenPath}" : "/"
        def url = "http://localhost:8080${path}"
        if (logger.isDebugEnabled()) {
            logger.debug("WebFacadeStub.getRequestUrl() returning: ${url}")
        }
        return url
    }
    
    @Override
    Map<String, Object> getParameters() {
        Map<String, Object> combined = [:]
        combined.putAll(parameters)
        combined.putAll(getRequestParameters())
        combined.putAll(getSessionAttributes())
        combined.putAll(getRequestAttributes())
        combined.putAll(getApplicationAttributes())
        return combined
    }
    
    @Override
    HttpServletRequest getRequest() { return httpServletRequest }
    
    @Override
    Map<String, Object> getRequestAttributes() { return requestAttributes }
    
    @Override
    Map<String, Object> getRequestParameters() { return parameters }
    
    @Override
    Map<String, Object> getSecureRequestParameters() { return parameters }
    
    @Override
    String getHostName(boolean withPort) {
        return withPort ? "localhost:8080" : "localhost"
    }
    
    @Override
    String getPathInfo() { 
        if (logger.isDebugEnabled()) {
            logger.debug("WebFacadeStub.getPathInfo() called - screenPath: ${screenPath}")
        }
        // For standalone screens, return empty path to render the screen itself
        // For screens with subscreen paths, return the relative path
        def pathInfo = screenPath ? "/${screenPath}" : ""
        if (logger.isDebugEnabled()) {
            logger.debug("WebFacadeStub.getPathInfo() returning: ${pathInfo}")
        }
        return pathInfo
    }
    
    @Override
    ArrayList<String> getPathInfoList() {
        if (logger.isDebugEnabled()) {
            logger.debug("WebFacadeStub.getPathInfoList() called - screenPath: ${screenPath}")
        }
        // IMPORTANT: Don't delegate to WebFacadeImpl - it expects real HTTP servlet context
        // Return mock path info for MCP screen rendering based on actual screen path
        def pathInfo = getPathInfo()
        def pathList = new ArrayList<String>()
        if (pathInfo && pathInfo.startsWith("/")) {
            // Split path and filter out empty parts
            def pathParts = pathInfo.substring(1).split("/") as List
            pathList = new ArrayList<String>(pathParts.findAll { it && it.toString().length() > 0 })
        }
        if (logger.isDebugEnabled()) {
            logger.debug("WebFacadeStub.getPathInfoList() returning: ${pathList} (from pathInfo: ${pathInfo})")
        }
        return pathList
    }
    
    @Override
    String getRequestBodyText() { return null }
    
    @Override
    String getResourceDistinctValue() { return "test" }
    
    @Override
    HttpServletResponse getResponse() { return httpServletResponse }
    
    @Override
    HttpSession getSession() { return httpSession }
    
    @Override
    Map<String, Object> getSessionAttributes() { return sessionAttributes }
    
    @Override
    String getSessionToken() { return "test-token" }
    
    @Override
    ServletContext getServletContext() { 
        return new MockServletContext() 
    }
    
    @Override
    Map<String, Object> getApplicationAttributes() { return applicationAttributes }
    
    @Override
    String getWebappRootUrl(boolean requireFullUrl, Boolean useEncryption) {
        return requireFullUrl ? "http://localhost:8080" : ""
    }
    
    @Override
    Map<String, Object> getErrorParameters() { return errorParameters }
    
    @Override
    List<MessageInfo> getSavedMessages() { return savedMessages }
    
    @Override
    List<MessageInfo> getSavedPublicMessages() { return savedPublicMessages }
    
    @Override
    List<String> getSavedErrors() { return savedErrors }
    
    @Override
    List<ValidationError> getSavedValidationErrors() { return savedValidationErrors }
    
    @Override
    List<ValidationError> getFieldValidationErrors(String fieldName) {
        return savedValidationErrors.findAll { it.field == fieldName }
    }
    
    @Override
    List<Map> getScreenHistory() { return screenHistory }
    
    @Override
    void sendJsonResponse(Object responseObj) {
        if (!skipJsonSerialize) {
            this.responseJsonObj = responseObj
            this.responseText = ContextJavaUtil.jacksonMapper.writeValueAsString(responseObj)
        } else {
            this.responseJsonObj = responseObj
            this.responseText = responseObj.toString()
        }
    }
    
    @Override
    void sendJsonError(int statusCode, String message, Throwable origThrowable) {
        this.responseText = "Error ${statusCode}: ${message}"
    }
    
    @Override
    void sendTextResponse(String text) {
        this.responseText = text
    }
    
    @Override
    void sendTextResponse(String text, String contentType, String filename) {
        this.responseText = text
    }
    
    @Override
    void sendResourceResponse(String location) {
        this.responseText = "Resource: ${location}"
    }
    
    @Override
    void sendResourceResponse(String location, boolean inline) {
        this.responseText = "Resource: ${location} (inline: ${inline})"
    }
    
    @Override
    void sendError(int errorCode, String message, Throwable origThrowable) {
        this.responseText = "Error ${errorCode}: ${message}"
    }
    
    @Override
    void handleJsonRpcServiceCall() {
        this.responseText = "JSON-RPC not implemented in stub"
    }
    
    @Override
    void handleEntityRestCall(List<String> extraPathNameList, boolean masterNameInPath) {
        this.responseText = "Entity REST not implemented in stub"
    }
    
    @Override
    void handleServiceRestCall(List<String> extraPathNameList) {
        this.responseText = "Service REST not implemented in stub"
    }
    
    @Override
    void handleSystemMessage(List<String> extraPathNameList) {
        this.responseText = "System message not implemented in stub"
    }
    
    // Helper methods for ScreenTestImpl
    String getResponseText() { 
        if (responseText != null) {
            logger.info("getResponseText: returning responseText (length: ${responseText.length()})")
            return responseText
        }
        if (httpServletResponse instanceof MockHttpServletResponse) {
            // Flush the writer to ensure all content is captured
            try {
                httpServletResponse.getWriter().flush()
            } catch (IOException e) {
                logger.warn("Error flushing response writer: ${e.message}")
            }
            def content = ((MockHttpServletResponse) httpServletResponse).getResponseContent()
            logger.info("getResponseText: returning content from mock response (length: ${content?.length() ?: 0})")
            return content
        }
        logger.warn("getResponseText: httpServletResponse is not MockHttpServletResponse: ${httpServletResponse?.getClass()?.getName()}")
        return null
    }
    Object getResponseJsonObj() { return responseJsonObj }
    
    // Mock HTTP classes
    static class MockHttpServletRequest implements HttpServletRequest {
        private final Map<String, Object> parameters
        private final String method
        private HttpSession session
        private String screenPath
        private String remoteUser = null
        private java.security.Principal userPrincipal = null
        
        MockHttpServletRequest(Map<String, Object> parameters, String method, HttpSession session = null, String screenPath = null) {
            this.parameters = parameters ?: [:]
            this.method = method ?: "GET"
            this.session = session
            this.screenPath = screenPath
            
            // Extract user information from session attributes for authentication
            if (session) {
                def username = session.getAttribute("username")
                def userId = session.getAttribute("userId")
                if (username) {
                    this.remoteUser = username as String
                    this.userPrincipal = new java.security.Principal() {
                        String getName() { return username as String }
                    }
                }
            }
        }
        
        @Override String getMethod() { return method }
        @Override String getScheme() { return "http" }
        @Override String getServerName() { return "localhost" }
        @Override int getServerPort() { return 8080 }
        @Override String getRequestURI() { 
            // Build URI based on actual screen path
            def path = screenPath ? "/${screenPath}" : "/"
            return path
        }
        @Override String getContextPath() { return "" }
        @Override String getServletPath() { return "" }
        @Override String getQueryString() { return null }
        @Override String getParameter(String name) { return parameters.get(name) as String }
        @Override Map<String, String[]> getParameterMap() { 
            return parameters.collectEntries { k, v -> [k, [v?.toString()] as String[]] }
        }
        @Override String[] getParameterValues(String name) { 
            def value = parameters.get(name)
            return value ? [value.toString()] as String[] : null
        }
        @Override HttpSession getSession() { return session }
        @Override HttpSession getSession(boolean create) { return session }
        @Override String getHeader(String name) { return null }
        @Override java.util.Enumeration<String> getHeaderNames() { return Collections.enumeration([]) }
        @Override java.util.Enumeration<String> getHeaders(String name) { return Collections.enumeration([]) }
        @Override String getRemoteAddr() { return "127.0.0.1" }
        @Override String getRemoteHost() { return "localhost" }
        @Override boolean isSecure() { return false }
        @Override String getCharacterEncoding() { return "UTF-8" }
        @Override void setCharacterEncoding(String env) throws java.io.UnsupportedEncodingException {}
        @Override int getContentLength() { return 0 }
        @Override String getContentType() { return null }
        @Override java.io.BufferedReader getReader() throws java.io.IOException { 
            return new BufferedReader(new StringReader("")) 
        }
        @Override String getProtocol() { return "HTTP/1.1" }
        
        // Other required methods with minimal implementations
        @Override Object getAttribute(String name) { return null }
        @Override void setAttribute(String name, Object value) {}
        @Override void removeAttribute(String name) {}
        @Override java.util.Enumeration<String> getAttributeNames() { return Collections.enumeration([]) }
        @Override String getAuthType() { return null }
        @Override String getRemoteUser() { return remoteUser }
        @Override boolean isUserInRole(String role) { return false }
        @Override java.security.Principal getUserPrincipal() { return userPrincipal }
        @Override String getRequestedSessionId() { return null }
        @Override StringBuffer getRequestURL() { 
            // Build URL based on actual screen path
            def path = screenPath ? "/${screenPath}" : "/"
            return new StringBuffer("http://localhost:8080${path}")
        }
        @Override String getPathInfo() { 
            // Return path info based on actual screen path
            return screenPath ? "/${screenPath}" : "/"
        }
        @Override String getPathTranslated() { return null }
        @Override boolean isRequestedSessionIdValid() { return false }
        @Override boolean isRequestedSessionIdFromCookie() { return false }
        @Override boolean isRequestedSessionIdFromURL() { return false }
        @Override java.util.Locale getLocale() { return Locale.US }
        @Override java.util.Enumeration<java.util.Locale> getLocales() { return Collections.enumeration([Locale.US]) }
        @Override javax.servlet.ServletInputStream getInputStream() throws java.io.IOException { 
            return new javax.servlet.ServletInputStream() {
                @Override boolean isReady() { return true }
                @Override void setReadListener(javax.servlet.ReadListener readListener) {}
                @Override int read() throws java.io.IOException { return -1 }
                @Override boolean isFinished() { return true }
            }
        }
        @Override String getLocalAddr() { return "127.0.0.1" }
        @Override String getLocalName() { return "localhost" }
        @Override int getLocalPort() { return 8080 }
        @Override ServletContext getServletContext() { return null }
        @Override boolean isAsyncStarted() { return false }
        @Override boolean isAsyncSupported() { return false }
        @Override javax.servlet.AsyncContext getAsyncContext() { return null }
        @Override javax.servlet.DispatcherType getDispatcherType() { return null }
        
        // Additional required methods for HttpServletRequest
        @Override long getContentLengthLong() { return 0 }
        @Override java.util.Enumeration<String> getParameterNames() { return Collections.enumeration(parameters.keySet()) }
        @Override javax.servlet.RequestDispatcher getRequestDispatcher(String path) { return null }
        @Override String getRealPath(String path) { return null }
        @Override int getRemotePort() { return 0 }
        @Override javax.servlet.AsyncContext startAsync() { return null }
        @Override javax.servlet.AsyncContext startAsync(javax.servlet.ServletRequest request, javax.servlet.ServletResponse response) { return null }
        @Override javax.servlet.http.Cookie[] getCookies() { return null }
        @Override long getDateHeader(String name) { return 0 }
        @Override int getIntHeader(String name) { return 0 }
        @Override String changeSessionId() { return session ? session.getId() : "mock-session-id" }
        @Override boolean isRequestedSessionIdFromUrl() { return false }
        @Override boolean authenticate(javax.servlet.http.HttpServletResponse response) { return false }
        @Override void login(String username, String password) {}
        @Override void logout() {}
        @Override java.util.Collection<javax.servlet.http.Part> getParts() { return [] }
        @Override javax.servlet.http.Part getPart(String name) { return null }
        @Override <T extends javax.servlet.http.HttpUpgradeHandler> T upgrade(Class<T> handlerClass) { return null }
    }
    
    static class MockHttpServletResponse implements HttpServletResponse {
        private StringWriter writer = new StringWriter()
        private PrintWriter printWriter = new PrintWriter(writer)
        private HttpSession mockSession
        private int status = 200
        private String contentType = "text/html"
        private String characterEncoding = "UTF-8"
        private Map<String, String> headers = [:]
        
        void setMockSession(HttpSession session) { this.mockSession = session }
        
        @Override PrintWriter getWriter() throws java.io.IOException { return printWriter }
        @Override javax.servlet.ServletOutputStream getOutputStream() throws java.io.IOException {
            return new javax.servlet.ServletOutputStream() {
                @Override boolean isReady() { return true }
                @Override void setWriteListener(javax.servlet.WriteListener writeListener) {}
                @Override void write(int b) throws java.io.IOException { writer.write(b) }
            }
        }
        
        @Override void setStatus(int sc) { this.status = sc }
        @Override int getStatus() { return status }
        @Override void setContentType(String type) { this.contentType = type }
        @Override String getContentType() { return contentType }
        @Override void setCharacterEncoding(String charset) { this.characterEncoding = charset }
        @Override String getCharacterEncoding() { return characterEncoding }
        @Override void setHeader(String name, String value) { headers[name] = value }
        @Override void addHeader(String name, String value) { headers[name] = value }
        @Override String getHeader(String name) { return headers[name] }
        @Override java.util.Collection<String> getHeaders(String name) { 
            return headers[name] ? [headers[name]] : [] 
        }
        @Override java.util.Collection<String> getHeaderNames() { return headers.keySet() }
        @Override void setContentLength(int len) {}
        @Override void setContentLengthLong(long len) {}
        @Override void setBufferSize(int size) {}
        @Override int getBufferSize() { return 0 }
        @Override void flushBuffer() throws java.io.IOException { printWriter.flush() }
        @Override void resetBuffer() {}
        @Override boolean isCommitted() { return false }
        @Override void reset() {}
        @Override Locale getLocale() { return Locale.US }
        
        String getResponseContent() { return writer.toString() }
        
        // Other required methods with minimal implementations
        @Override String encodeURL(String url) { return url }
        @Override String encodeRedirectURL(String url) { return url }
        @Override String encodeUrl(String url) { return url }
        @Override String encodeRedirectUrl(String url) { return url }
        @Override void sendError(int sc, String msg) throws java.io.IOException { status = sc }
        @Override void sendError(int sc) throws java.io.IOException { status = sc }
        @Override void sendRedirect(String location) throws java.io.IOException {}
        @Override void setDateHeader(String name, long date) {}
        @Override void addDateHeader(String name, long date) {}
        @Override void setIntHeader(String name, int value) {}
        @Override void addIntHeader(String name, int value) {}
        @Override boolean containsHeader(String name) { return headers.containsKey(name) }
        
        // Additional required methods for HttpServletResponse
        @Override void setLocale(Locale locale) {}
        @Override void addCookie(javax.servlet.http.Cookie cookie) {}
        @Override void setStatus(int sc, String sm) { this.status = sc }
    }
    
    static class MockHttpSession implements HttpSession {
        private final Map<String, Object> attributes
        private long creationTime = System.currentTimeMillis()
        private String id = "mock-session-" + System.currentTimeMillis()
        
        MockHttpSession(Map<String, Object> attributes) {
            this.attributes = attributes ?: [:]
        }
        
        @Override Object getAttribute(String name) { return attributes.get(name) }
        @Override void setAttribute(String name, Object value) { attributes[name] = value }
        @Override void removeAttribute(String name) { attributes.remove(name) }
        @Override java.util.Enumeration<String> getAttributeNames() { return Collections.enumeration(attributes.keySet()) }
        @Override long getCreationTime() { return creationTime }
        @Override String getId() { return id }
        @Override long getLastAccessedTime() { return System.currentTimeMillis() }
        @Override javax.servlet.ServletContext getServletContext() { return null }
        @Override void setMaxInactiveInterval(int interval) {}
        @Override int getMaxInactiveInterval() { return 1800 }
        @Override javax.servlet.http.HttpSessionContext getSessionContext() { return null }
        @Override void invalidate() {}
        @Override boolean isNew() { return false }
        @Override void putValue(String name, Object value) { setAttribute(name, value) }
        @Override Object getValue(String name) { return getAttribute(name) }
        @Override void removeValue(String name) { removeAttribute(name) }
        @Override String[] getValueNames() { return attributes.keySet() as String[] }
    }
    
    static class MockServletContext implements ServletContext {
        private final Map<String, Object> attributes = [:]
        
        @Override Object getAttribute(String name) { return attributes.get(name) }
        @Override void setAttribute(String name, Object value) { attributes[name] = value }
        @Override void removeAttribute(String name) { attributes.remove(name) }
        @Override java.util.Enumeration<String> getAttributeNames() { return Collections.enumeration(attributes.keySet()) }
        @Override String getServletContextName() { return "MockServletContext" }
        @Override String getServerInfo() { return "Mock Server" }
        @Override int getMajorVersion() { return 4 }
        @Override int getMinorVersion() { return 0 }
        @Override String getMimeType(String file) { return null }
        @Override String getRealPath(String path) { return null }
        @Override java.io.InputStream getResourceAsStream(String path) { return null }
        @Override java.net.URL getResource(String path) throws java.net.MalformedURLException { return null }
        @Override javax.servlet.RequestDispatcher getRequestDispatcher(String path) { return null }
        @Override javax.servlet.RequestDispatcher getNamedDispatcher(String name) { return null }
        @Override String getInitParameter(String name) { return null }
        @Override java.util.Enumeration<String> getInitParameterNames() { return Collections.enumeration([]) }
        @Override boolean setInitParameter(String name, String value) { return false }
        @Override String getContextPath() { return "" }
        @Override ServletContext getContext(String uripath) { return null }
        @Override int getEffectiveMajorVersion() { return 4 }
        @Override int getEffectiveMinorVersion() { return 0 }
        @Override javax.servlet.Servlet getServlet(String name) throws javax.servlet.ServletException { return null }
        @Override java.util.Enumeration<javax.servlet.Servlet> getServlets() { return Collections.enumeration([]) }
        @Override java.util.Enumeration<String> getServletNames() { return Collections.enumeration([]) }
        @Override void log(String msg) {}
        @Override void log(Exception exception, String msg) {}
        @Override void log(String msg, Throwable throwable) {}
        
        // Additional required methods for ServletContext
        @Override java.util.Set<String> getResourcePaths(String path) { return null }
        @Override javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, String className) { return null }
        @Override javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, javax.servlet.Servlet servlet) { return null }
        @Override javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, Class<? extends javax.servlet.Servlet> servletClass) { return null }
        @Override javax.servlet.ServletRegistration.Dynamic addJspFile(String jspName, String jspFile) { return null }
        @Override <T extends javax.servlet.Servlet> T createServlet(Class<T> clazz) { return null }
        @Override javax.servlet.ServletRegistration getServletRegistration(String servletName) { return null }
        @Override java.util.Map<String, ? extends javax.servlet.ServletRegistration> getServletRegistrations() { return [:] }
        @Override javax.servlet.FilterRegistration.Dynamic addFilter(String filterName, String className) { return null }
        @Override javax.servlet.FilterRegistration.Dynamic addFilter(String filterName, javax.servlet.Filter filter) { return null }
        @Override javax.servlet.FilterRegistration.Dynamic addFilter(String filterName, Class<? extends javax.servlet.Filter> filterClass) { return null }
        @Override <T extends javax.servlet.Filter> T createFilter(Class<T> clazz) { return null }
        @Override javax.servlet.FilterRegistration getFilterRegistration(String filterName) { return null }
        @Override java.util.Map<String, ? extends javax.servlet.FilterRegistration> getFilterRegistrations() { return [:] }
        @Override javax.servlet.SessionCookieConfig getSessionCookieConfig() { return null }
        @Override void setSessionTrackingModes(java.util.Set<javax.servlet.SessionTrackingMode> sessionTrackingModes) {}
        @Override java.util.Set<javax.servlet.SessionTrackingMode> getDefaultSessionTrackingModes() { return [] as Set }
        @Override java.util.Set<javax.servlet.SessionTrackingMode> getEffectiveSessionTrackingModes() { return [] as Set }
        @Override void addListener(String className) {}
        @Override void addListener(EventListener listener) {}
        @Override void addListener(Class<? extends EventListener> listenerClass) {}
        @Override <T extends EventListener> T createListener(Class<T> clazz) { return null }
        @Override javax.servlet.descriptor.JspConfigDescriptor getJspConfigDescriptor() { return null }
        @Override ClassLoader getClassLoader() { return null }
        @Override void declareRoles(String... roleNames) {}
        @Override String getVirtualServerName() { return "localhost" }
        @Override int getSessionTimeout() { return 30 }
        @Override void setSessionTimeout(int sessionTimeout) {}
        @Override String getRequestCharacterEncoding() { return "UTF-8" }
        @Override void setRequestCharacterEncoding(String encoding) {}
        @Override String getResponseCharacterEncoding() { return "UTF-8" }
        @Override void setResponseCharacterEncoding(String encoding) {}
    }
}
