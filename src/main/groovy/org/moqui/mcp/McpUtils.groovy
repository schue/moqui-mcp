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
import org.moqui.util.MNode

class McpUtils {
    /**
     * Convert a Moqui screen path to an MCP tool name.
     * Format: moqui_<Component>_<Path_Parts>
     * Example: component://PopCommerce/screen/PopCommerceAdmin/Catalog.xml -> moqui_PopCommerce_PopCommerceAdmin_Catalog
     */
    static String getToolName(String screenPath) {
        if (!screenPath) return null
        
        String cleanPath = screenPath
        if (cleanPath.startsWith("component://")) cleanPath = cleanPath.substring(12)
        if (cleanPath.endsWith(".xml")) cleanPath = cleanPath.substring(0, cleanPath.length() - 4)
        
        List<String> parts = cleanPath.split('/').toList()
        if (parts.size() > 1 && parts[1] == "screen") {
            parts.remove(1)
        }
        
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
        if (parts.size() == 1) {
             return "component://${component}/screen/${component}.xml"
        }

        String path = parts.subList(1, parts.size()).join('/')
        return "component://${component}/screen/${path}.xml"
    }

    /**
     * Decodes a tool name into a screen path and potential subscreen path by walking the component structure.
     * This handles cases where a single XML file contains multiple nested subscreens.
     */
    static Map decodeToolName(String toolName, ExecutionContext ec) {
        if (!toolName || !toolName.startsWith("moqui_")) return [:]
        
        String cleanName = toolName.substring(6) // Remove moqui_
        List<String> parts = cleanName.split('_').toList()
        String component = parts[0]
        
        String currentPath = "component://${component}/screen"
        List<String> subNameParts = []
        
        // Walk down the parts to find where the XML file ends and subscreens begin
        for (int i = 1; i < parts.size(); i++) {
            String part = parts[i]
            String nextPath = "${currentPath}/${part}"
            if (ec.resource.getLocationReference(nextPath + ".xml").getExists()) {
                currentPath = nextPath
                subNameParts = [] // Reset subscreens if we found a deeper file
            } else {
                subNameParts << part
            }
        }
        
        return [
            screenPath: currentPath + ".xml",
            subscreenName: subNameParts ? subNameParts.join("_") : null
        ]
    }
}
