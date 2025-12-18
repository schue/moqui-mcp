package org.moqui.mcp

import org.moqui.util.MNode

class McpUtils {
    /**
     * Convert a Moqui screen path to an MCP tool name.
     * Preserves case to ensure reversibility.
     * Format: moqui_<Component>_<Path_Parts>
     * Example: component://PopCommerce/screen/PopCommerceAdmin/Catalog.xml -> moqui_PopCommerce_PopCommerceAdmin_Catalog
     */
    static String getToolName(String screenPath) {
        if (!screenPath) return null
        
        // Strip component:// prefix and .xml suffix
        String cleanPath = screenPath
        if (cleanPath.startsWith("component://")) cleanPath = cleanPath.substring(12)
        if (cleanPath.endsWith(".xml")) cleanPath = cleanPath.substring(0, cleanPath.length() - 4)
        
        List<String> parts = cleanPath.split('/').toList()
        
        // Remove 'screen' if it's the second part (standard structure: component/screen/...)
        if (parts.size() > 1 && parts[1] == "screen") {
            parts.remove(1)
        }
        
        // Join with underscores and prefix
        return "moqui_" + parts.join('_')
    }

    /**
     * Convert an MCP tool name back to a Moqui screen path.
     * Assumes standard component://<Component>/screen/<Path>.xml structure.
     */
    static String getScreenPath(String toolName) {
        if (!toolName || !toolName.startsWith("moqui_")) return null
        
        String cleanName = toolName.substring(6) // Remove moqui_
        List<String> parts = cleanName.split('_').toList()
        
        if (parts.size() < 1) return null
        
        String component = parts[0]
        
        // If there's only one part (e.g. moqui_MyComponent), it might be a root or invalid
        // But usually we expect at least component and screen
        if (parts.size() == 1) {
            // Fallback for component roots? unlikely to be a valid screen path without 'screen' dir
             return "component://${component}/screen/${component}.xml"
        }

        // Re-insert 'screen' directory which is standard
        String path = parts.subList(1, parts.size()).join('/')
        return "component://${component}/screen/${path}.xml"
    }
}
