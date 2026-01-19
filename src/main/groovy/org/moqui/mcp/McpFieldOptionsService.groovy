package org.moqui.mcp

import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import groovy.json.JsonSlurper

/**
 * Service for getting screen field details including dropdown options via dynamic-options.
 * 
 * This implementation mirrors how the Moqui web UI handles autocomplete:
 * - Uses CustomScreenTestImpl with skipJsonSerialize(true) to call transitions
 * - Captures the raw JSON response via getJsonObject()
 * - Processes the response to extract options
 * 
 * See ScreenRenderImpl.getFieldOptions() in moqui-framework for the reference implementation.
 */
class McpFieldOptionsService {
    
    static service(String path, String fieldName, Map parameters, ExecutionContext ec) {
        ec.logger.info("======== MCP GetScreenDetails CALLED - CODE VERSION 3 (ScreenTest) =======")
        if (!path) throw new IllegalArgumentException("path is required")
        ec.logger.info("MCP GetScreenDetails: screen ${path}, field ${fieldName ?: 'all'}")

        def result = [screenPath: path, fields: [:]]
        try {
            def browseResult = ec.service.sync().name("McpServices.execute#ScreenAsMcpTool")
                .parameters([path: path, parameters: parameters ?: [:], renderMode: "mcp", sessionId: null])
                .call()

            ec.logger.info("=== browseResult: ${browseResult != null}, result exists: ${browseResult?.result != null} ===")

            if (!browseResult?.result?.content) {
                ec.logger.warn("No content from ScreenAsMcpTool")
                return result + [error: "No content from ScreenAsMcpTool"]
            }
            def rawText = browseResult.result.content[0].text
            if (!rawText || !rawText.startsWith("{")) {
                ec.logger.warn("Invalid JSON from ScreenAsMcpTool")
                return result + [error: "Invalid JSON from ScreenAsMcpTool"]
            }

            def resultObj = new JsonSlurper().parseText(rawText)
            def semanticState = resultObj?.semanticState
            def formMetadata = semanticState?.data?.formMetadata

            if (!(formMetadata instanceof Map)) {
                ec.logger.warn("formMetadata is not a Map: ${formMetadata?.class}")
                return result + [error: "No form metadata found"]
            }

            def allFields = [:]
            ec.logger.info("=== Processing formMetadata with ${formMetadata.size()} forms ===")
            formMetadata.each { formName, formItem ->
                ec.logger.info("=== Processing form: ${formName}, hasFields: ${formItem?.fields != null} ===")
                if (!(formItem instanceof Map) || !formItem.fields) return
                formItem.fields.each { field ->
                    if (!(field instanceof Map) || !field.name) return
                    
                    def fieldInfo = [
                        name: field.name,
                        title: field.title,
                        type: field.type,
                        required: field.required ?: false
                    ]
                    if (field.type == "dropdown" && field.options) fieldInfo.options = field.options

                    def dynamicOptions = field.dynamicOptions
                    if (dynamicOptions instanceof Map) {
                        fieldInfo.dynamicOptions = dynamicOptions
                        ec.logger.info("Found dynamicOptions for field ${field.name}: ${dynamicOptions}")
                        try {
                            fetchOptions(fieldInfo, path, parameters, dynamicOptions, ec)
                        } catch (Exception e) {
                            ec.logger.warn("Failed to fetch options for ${field.name}: ${e.message}", e)
                            fieldInfo.optionsError = e.message
                        }
                    }
                    allFields[field.name] = fieldInfo
                }
            }

            if (fieldName) {
                if (allFields[fieldName]) result.fields[fieldName] = allFields[fieldName]
                else result.error = "Field not found: ${fieldName}"
            } else {
                result.fields = allFields.collectEntries { k, v -> [k, v] }
            }
        } catch (Exception e) {
            ec.logger.error("MCP GetScreenDetails error: ${e.message}", e)
            result.error = e.message
        }
        return result
    }

    /**
     * Fetch options for a field with dynamic-options by calling the transition.
     * 
     * This uses CustomScreenTestImpl with skipJsonSerialize(true) to call the transition
     * and capture the raw JSON response - exactly how ScreenRenderImpl.getFieldOptions() works.
     */
    private static void fetchOptions(Map fieldInfo, String path, Map parameters, Map dynamicOptions, ExecutionContext ec) {
        ec.logger.info("=== fetchOptions START: ${fieldInfo.name} ===")
        def transitionName = dynamicOptions.transition
        if (!transitionName) {
            ec.logger.info("No transition specified for dynamic options")
            return
        }
        
        def optionParams = [:]
        
        // 1. Handle dependsOn (from form XML) - maps field values to service parameters
        if (dynamicOptions.dependsOn) {
            def depList = dynamicOptions.dependsOn instanceof String ? 
                new JsonSlurper().parseText(dynamicOptions.dependsOn) : dynamicOptions.dependsOn
            
            depList.each { dep ->
                def parts = dep.split('\\|')
                def fld = parts[0], prm = parts.size() > 1 ? parts[1] : fld
                def val = parameters?.get(fld)
                
                // Try common form map names if not found at top level
                if (val == null) {
                    ['fieldValues', 'fieldValuesMap', 'formValues', 'formValuesMap', 'formMap'].each { mapName ->
                        def mapVal = parameters?.get(mapName as String)
                        if (mapVal instanceof Map) {
                            val = mapVal.get(fld)
                            if (val != null) return
                        }
                    }
                }
                if (val != null) optionParams[prm] = val
            }
        }
 
        // 2. Handle serverSearch fields
        // If serverSearch is true AND no term is provided, skip fetching (matches framework behavior)
        // The framework's getFieldOptions() skips server-search fields entirely for initial load
        def isServerSearch = dynamicOptions.serverSearch == true || dynamicOptions.serverSearch == "true"
        if (isServerSearch) {
            if (parameters?.term != null && parameters.term.toString().length() > 0) {
                optionParams.term = parameters.term
            } else {
                // Skip fetching options for server-search fields without a term
                ec.logger.info("Skipping server-search field ${fieldInfo.name} - no term provided")
                return
            }
        }
 
        // 3. Use CustomScreenTestImpl with skipJsonSerialize to call the transition
        // This is exactly how ScreenRenderImpl.getFieldOptions() works in the framework
        ec.logger.info("Calling transition ${transitionName} via CustomScreenTestImpl with skipJsonSerialize=true, params: ${optionParams}")
        
        try {
            def ecfi = (ExecutionContextFactoryImpl) ec.factory
            
            // Build transition path by appending transition name to screen path
            def fullPath = path
            if (!fullPath.endsWith('/')) fullPath += '/'
            fullPath += transitionName
            
            // Parse path segments for component-based resolution
            def pathSegments = []
            fullPath.split('/').each { if (it && it.trim()) pathSegments.add(it) }
            
            // Component-based resolution (same as ScreenAsMcpTool)
            // Path like "PopCommerce/PopCommerceAdmin/Party/FindParty/transition" becomes:
            // - rootScreen: component://PopCommerce/screen/PopCommerceAdmin.xml
            // - testScreenPath: Party/FindParty/transition
            def rootScreen = "component://webroot/screen/webroot.xml"
            def testScreenPath = fullPath
            
            if (pathSegments.size() >= 2) {
                def componentName = pathSegments[0]
                def rootScreenName = pathSegments[1]
                def compRootLoc = "component://${componentName}/screen/${rootScreenName}.xml"
                
                if (ec.resource.getLocationReference(compRootLoc).exists) {
                    ec.logger.info("fetchOptions: Using component root: ${compRootLoc}")
                    rootScreen = compRootLoc
                    testScreenPath = pathSegments.size() > 2 ? pathSegments[2..-1].join('/') : ""
                }
            }
            
            // Use CustomScreenTestImpl with skipJsonSerialize - like ScreenRenderImpl.getFieldOptions()
            def screenTest = new CustomScreenTestImpl(ecfi)
                .rootScreen(rootScreen)
                .skipJsonSerialize(true)
                .auth(ec.user.username)
            
            ec.logger.info("Rendering transition path: ${testScreenPath} (from root: ${rootScreen})")
            def str = screenTest.render(testScreenPath, optionParams, "GET")
            
            // Get JSON object directly (like web UI does)
            def jsonObj = str.getJsonObject()
            ec.logger.info("Transition returned jsonObj: ${jsonObj?.getClass()?.simpleName}, size: ${jsonObj instanceof Collection ? jsonObj.size() : 'N/A'}")
            
            // Extract value-field and label-field from dynamic-options config
            def valueField = dynamicOptions.valueField ?: dynamicOptions.'value-field' ?: 'value'
            def labelField = dynamicOptions.labelField ?: dynamicOptions.'label-field' ?: 'label'
            
            // Process the JSON response - same logic as ScreenRenderImpl.getFieldOptions()
            List optsList = null
            if (jsonObj instanceof List) {
                optsList = (List) jsonObj
            } else if (jsonObj instanceof Map) {
                Map jsonMap = (Map) jsonObj
                // Try 'options' key first (standard pattern)
                def optionsObj = jsonMap.get("options")
                if (optionsObj instanceof List) {
                    optsList = (List) optionsObj
                } else if (jsonMap.get("resultList") instanceof List) {
                    // Some services return resultList
                    optsList = (List) jsonMap.get("resultList")
                }
            }
            
            if (optsList != null && optsList.size() > 0) {
                fieldInfo.options = optsList.collect { entryObj ->
                    if (entryObj instanceof Map) {
                        Map entryMap = (Map) entryObj
                        // Try configured fields first, then common fallbacks
                        def value = entryMap.get(valueField) ?: 
                                   entryMap.get('value') ?: 
                                   entryMap.get('geoId') ?: 
                                   entryMap.get('enumId') ?: 
                                   entryMap.get('id') ?: 
                                   entryMap.get('key')
                        def label = entryMap.get(labelField) ?: 
                                   entryMap.get('label') ?: 
                                   entryMap.get('description') ?: 
                                   entryMap.get('name') ?: 
                                   entryMap.get('text') ?:
                                   value?.toString()
                        [value: value, label: label]
                    } else {
                        [value: entryObj, label: entryObj?.toString()]
                    }
                }.findAll { it.value != null }
                
                ec.logger.info("Successfully extracted ${fieldInfo.options.size()} autocomplete options via ScreenTest")
            } else {
                ec.logger.info("No options found in transition response")
                
                // Check if there was output but no JSON (might be an error)
                def output = str.getOutput()
                if (output && output.length() > 0 && output.length() < 500) {
                    ec.logger.warn("Transition output (no JSON): ${output}")
                }
            }
            
        } catch (Exception e) {
            ec.logger.warn("Error calling transition ${transitionName}: ${e.message}", e)
            fieldInfo.optionsError = "Transition call failed: ${e.message}"
        }
    }
}
