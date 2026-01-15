package org.moqui.mcp

import org.moqui.context.ExecutionContext

class McpFieldOptionsService {
    static service(String path, String fieldName, Map parameters, ExecutionContext ec) {
        if (!path) {
            throw new IllegalArgumentException("path is required")
        }
        
        ec.logger.info("MCP GetScreenDetails: Getting details for screen ${path}, field ${fieldName ?: 'all'}")
        
        def result = [
            screenPath: path,
            fields: [:]
        ]
        
        try {
            // First, render screen to get form metadata (including dynamicOptions)
            def browseScreenCallParams = [
                path: path,
                parameters: parameters ?: [:],
                renderMode: "mcp",
                sessionId: null,
                terse: false
            ]
            
            def browseResult = ec.service.sync().name("McpServices.execute#ScreenAsMcpTool")
                .parameters(browseScreenCallParams)
                .call()
            
            if (browseResult?.result?.content?.size() > 0) {
                def rawText = browseResult.result.content[0].text
                if (rawText && rawText.startsWith("{")) {
                    def resultObj = new groovy.json.JsonSlurper().parseText(rawText)
                    def semanticData = resultObj?.semanticState?.data
                    
                    if (semanticData?.containsKey("formMetadata")) {
                        def formMetadata = semanticData.formMetadata
                        def allFields = [:]
                        
                        if (formMetadata instanceof Map) {
                            formMetadata.each { formName, formItem ->
                                if (formItem instanceof Map && formItem.containsKey("fields")) {
                                    def fieldList = formItem.fields
                                    if (fieldList instanceof Collection) {
                                        fieldList.each { field ->
                                            if (field instanceof Map && field.containsKey("name")) {
                                                def fieldInfo = [
                                                    name: field.name,
                                                    title: field.title,
                                                    type: field.type,
                                                    required: field.required ?: false
                                                ]
                                                
                                                // Add dropdown options if available (static options)
                                                if (field.type == "dropdown" && field.containsKey("options")) {
                                                    fieldInfo.options = field.options
                                                }
                                                
                                                // Add dynamic options metadata and actually fetch the options
                                                def skipField = false
                                                if (field.containsKey("dynamicOptions") && !skipField) {
                                                    def dynamicOptions = field.dynamicOptions
                                                    fieldInfo.dynamicOptions = dynamicOptions
                                                      
                                                      try {
                                                        def serviceName = dynamicOptions.containsKey("service") ? dynamicOptions.service : null
                                                        def transitionName = dynamicOptions.containsKey("transition") ? dynamicOptions.transition : null
                                                        def optionParams = [:]
                                                          
                                                        // Parse inParameterMap if specified (extracted from transition XML)
                                                        def inParameterMap = [:]
                                                        if (dynamicOptions.containsKey("inParameterMap") && dynamicOptions.inParameterMap && dynamicOptions.inParameterMap.trim()) {
                                                            // Parse in-map format: "[target1:source1,target2:source2]"
                                                            def mapContent = dynamicOptions.inParameterMap.trim()
                                                            if (mapContent.startsWith("[") && mapContent.endsWith("]")) {
                                                                def innerContent = mapContent.substring(1, mapContent.length() - 1)
                                                                innerContent.split(',').each { mapping ->
                                                                    def colonIndex = mapping.indexOf(':')
                                                                    if (colonIndex > 0) {
                                                                        def targetParam = mapping.substring(0, colonIndex).trim()
                                                                        def sourceFields = mapping.substring(colonIndex + 1).trim()
                                                                        // Handle multiple source fields separated by comma
                                                                        sourceFields.split(',').each { sourceField ->
                                                                            def sourceValue = parameters?.get(sourceField.trim())
                                                                            if (sourceValue != null) {
                                                                                inParameterMap[targetParam] = sourceValue
                                                                                ec.logger.info("MCP GetScreenDetails: Mapped in-param ${sourceField} -> ${targetParam} = ${sourceValue}")
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        
                                                        // Handle depends-on fields and parameter overrides
                                                        ec.logger.info("MCP GetScreenDetails: Processing depends-on for field ${field.name}")
                                                        
                                                        // Parse depends-on list (may include parameter overrides like "field|parameter")
                                                        def dependsOnFields = []
                                                        if (dynamicOptions.containsKey("dependsOn") && dynamicOptions.dependsOn) {
                                                            if (dynamicOptions.dependsOn instanceof String) {
                                                                dependsOnFields = new groovy.json.JsonSlurper().parseText(dynamicOptions.dependsOn)
                                                            } else if (dynamicOptions.dependsOn instanceof List) {
                                                                dependsOnFields = dynamicOptions.dependsOn
                                                            }
                                                        }
                                                        
                                                        // Process each depends-on field with potential parameter override
                                                        dependsOnFields.each { depFieldOrTuple ->
                                                            def depField = depFieldOrTuple
                                                            def depParameter = depFieldOrTuple  // Default: use field name as parameter name
                                                            
                                                            // Check if depends-on item is a "field|parameter" tuple
                                                            if (depFieldOrTuple instanceof String && depFieldOrTuple.contains("|")) {
                                                                def parts = depFieldOrTuple.split("\\|")
                                                                if (parts.size() == 2) {
                                                                    depField = parts[0].trim()
                                                                    depParameter = parts[1].trim()
                                                                }
                                                            }
                                                            
                                                            def depValue = parameters?.get(depField)
                                                            if (depValue == null) {
                                                                ec.logger.info("MCP GetScreenDetails: Depends-on field ${depField} has no value in parameters")
                                                            } else {
                                                                ec.logger.info("MCP GetScreenDetails: Depends-on field ${depField} = ${depValue}, targetParam = ${depParameter}")
                                                                // Add to optionParams - use depParameter as key if specified, otherwise use depField
                                                                optionParams[depParameter ?: depField] = depValue
                                                            }
                                                        }
                                                        
                                                         // For transitions with web-send-json-response, try to extract and call service directly
                                                         // These transitions wrap services like BasicServices.get#GeoRegionsForDropDown
                                                         if (dynamicOptions.containsKey("serviceName") && dynamicOptions.serviceName) {
                                                            // Direct service call - use extracted service name from transition XML
                                                            ec.logger.info("MCP GetScreenDetails: Calling direct service ${dynamicOptions.serviceName} for field ${field.name} with optionParams: ${optionParams}")
                                                            def optionsResult = ec.service.sync().name(dynamicOptions.serviceName).parameters(optionParams).call()
                                                            if (optionsResult && optionsResult.resultList) {
                                                                def optionsList = []
                                                                    optionsResult.resultList.each { opt ->
                                                                        if (opt instanceof Map) {
                                                                            def key = opt.geoId ?: opt.value ?: opt.key ?: opt.enumId
                                                                            def label = opt.label ?: opt.description ?: opt.value
                                                                            optionsList << [value: key, label: label]
                                                                        }
                                                                    }
                                                                if (optionsList) {
                                                                    fieldInfo.options = optionsList
                                                                    ec.logger.info("MCP GetScreenDetails: Retrieved ${optionsList.size()} options via direct service call")
                                                                    allFields[field.name] = fieldInfo
                                                                    return // Skip remaining processing for this field
                                                                }
                                                            }
                                                        } else {
                                                            // Fallback for hardcoded transitions or when serviceName not available
                                                            ec.logger.info("MCP GetScreenDetails: No serviceName found, checking hardcoded transitions")
                                                            if (transitionName == "getGeoCountryStates" || transitionName == "getGeoStateCounties") {
                                                                def underlyingService = "org.moqui.impl.BasicServices.get#GeoRegionsForDropDown"
                                                                // Map depends-on field names to service parameter names (e.g., countryGeoId -> geoId)
                                                                def serviceParams = [:]
                                                                if (optionParams.containsKey("countryGeoId")) {
                                                                    serviceParams.geoId = optionParams.countryGeoId
                                                                }
                                                                if (optionParams.containsKey("stateGeoId")) {
                                                                    serviceParams.geoId = optionParams.stateGeoId
                                                                    serviceParams.geoTypeEnumId = "GEOT_COUNTY"
                                                                }
                                                                if (optionParams.containsKey("term")) {
                                                                    serviceParams.term = optionParams.term
                                                                }
                                                                ec.logger.info("MCP GetScreenDetails: Calling direct service ${underlyingService} for field ${field.name} with serviceParams: ${serviceParams}")
                                                                def optionsResult = ec.service.sync().name(underlyingService).parameters(serviceParams).call()
                                                            if (optionsResult && optionsResult.resultList) {
                                                                def optionsList = []
                                                                    optionsResult.resultList.each { opt ->
                                                                        if (opt instanceof Map) {
                                                                            def key = opt.geoId ?: opt.value ?: opt.key
                                                                            def label = opt.label ?: opt.value
                                                                            optionsList << [value: key, label: label]
                                                                        }
                                                                    }
                                                                 if (optionsList) {
                                                                    fieldInfo.options = optionsList
                                                                    ec.logger.info("MCP GetScreenDetails: Retrieved ${optionsList.size()} options via direct service call, returning from field processing")
                                                                    allFields[field.name] = fieldInfo
                                                                    return // Skip remaining processing for this field
                                                        }
                                                    }
                                                }
                                                // Fallback for transitions without direct service - try calling via ScreenAsMcpTool
                                                // This handles entity-find transitions and others without web-send-json-response
                                                if (transitionName && !dynamicOptions.containsKey("serviceName")) {
                                                    ec.logger.info("MCP GetScreenDetails: Calling transition ${transitionName} via ScreenAsMcpTool for field ${field.name}")
                                                    def transCallParams = [
                                                        path: "/" + path,
                                                        action: transitionName,
                                                        parameters: optionParams,
                                                        renderMode: "mcp",
                                                        sessionId: null
                                                    ]
                                                    def transResult = ec.service.sync().name("McpServices.execute#ScreenAsMcpTool").parameters(transCallParams).call()
                                                    
                                                    // Parse response - try to extract options from mcp render output
                                                    if (transResult?.result?.content?.size() > 0) {
                                                        def responseText = transResult.result.content[0].text
                                                        if (responseText) {
                                                            ec.logger.info("MCP GetScreenDetails: Transition returned ${responseText.length()} chars")
                                                            
                                                            // Try to parse as JSON first
                                                            try {
                                                                def jsonObj = new groovy.json.JsonSlurper().parseText(responseText)
                                                                
                                                                // Check for options in formMetadata
                                                                if (jsonObj.containsKey("formMetadata")) {
                                                                    def formData = jsonObj.formMetadata
                                                                    formData.each { formName, formItem ->
                                                                        if (formItem.containsKey("fields")) {
                                                                            def fieldOptions = formItem.fields.find { it.name == field.name }
                                                                            if (fieldOptions && fieldOptions.containsKey("options")) {
                                                                                fieldInfo.options = fieldOptions.options
                                                                                ec.logger.info("MCP GetScreenDetails: Retrieved ${fieldInfo.options.size()} options from formMetadata")
                                                                                allFields[field.name] = fieldInfo
                                                                                return
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                                
                                                                // Fallback to previous logic
                                                                if (jsonObj instanceof Map && jsonObj.containsKey("resultList")) {
                                                                    def resultList = jsonObj.resultList
                                                                    if (resultList instanceof List) {
                                                                        def optionsList = []
                                                                            resultList.each { opt ->
                                                                                if (opt instanceof Map) {
                                                                                    def key = opt.geoId ?: opt.value ?: opt.key ?: opt.enumId
                                                                                    def label = opt.label ?: opt.description ?: opt.value
                                                                                    optionsList << [value: key, label: label]
                                                                                }
                                                                            }
                                                                        if (optionsList) {
                                                                            fieldInfo.options = optionsList
                                                                            ec.logger.info("MCP GetScreenDetails: Retrieved ${optionsList.size()} options from transition ${transitionName}")
                                                                            allFields[field.name] = fieldInfo
                                                                            return
                                                                        }
                                                                    } else if (jsonObj instanceof Map && jsonObj.containsKey("options")) {
                                                                    fieldInfo.options = jsonObj.options
                                                                    } else if (jsonObj instanceof Map && jsonObj.containsKey("value")) {
                                                                    fieldInfo.options = [[value: jsonObj.value, label: jsonObj.value]]
                                                                } else {
                                                                    fieldInfo.optionsError = "Unrecognized response format: ${jsonObj.getClass().simpleName}"
                                                                }
                                                            } catch (Exception parseEx) {
                                                                ec.logger.warn("MCP GetScreenDetails: Failed to parse transition response: ${parseEx.message}")
                                                                fieldInfo.optionsError = "Transition call succeeded but response format not recognized. Try: moqui_browse_screens(path='${path}', action='${transitionName}')"
                                                            }
                                                        }
                                                    }
                                                            fieldInfo.optionsError = "Call moqui_browse_screens(path='\${path}', action='\${transitionName}') to fetch dropdown options"
                                                        }
                                                        if (serviceName) {
                                                            // Direct service call - same as frontend does
                                                            ec.logger.info("MCP GetScreenDetails: Calling service ${serviceName} for field ${field.name}")
                                                            def optionsResult = ec.service.sync().name(serviceName).parameters(optionParams).call()
                                                            if (optionsResult) {
                                                                def optionsList = []
                                                                    if (optionsResult instanceof Map && optionsResult.containsKey("resultList")) {
                                                                        def resultList = optionsResult.resultList
                                                                        if (resultList instanceof List) {
                                                                            resultList.each { opt ->
                                                                                if (opt instanceof Map) {
                                                                                    def key = opt.geoId ?: opt.value ?: opt.key
                                                                                    def label = opt.label ?: opt.value
                                                                                    optionsList << [value: key, label: label]
                                                                                }
                                                                            }
                                                                        }
                                                                    } else if (optionsResult instanceof List) {
                                                                        optionsList = optionsResult
                                                                    }
                                                                
                                                                if (optionsList) {
                                                                    fieldInfo.options = optionsList
                                                                    ec.logger.info("MCP GetScreenDetails: Retrieved ${optionsList.size()} options from service ${serviceName}")
                                                                }
                                                            }
                                                        } else if (transitionName) {
                                                            // For transitions, try calling via ScreenAsMcpTool with transition
                                                            ec.logger.info("MCP GetScreenDetails: Calling transition ${transitionName} for field ${field.name}")
                                                            
                                                            def transCallParams = [
                                                                path: "/" + path,
                                                                action: transitionName,
                                                                parameters: optionParams,
                                                                renderMode: "text",  // Get raw text response, not JSON
                                                                sessionId: null
                                                            ]
                                                            def transResult = ec.service.sync().name("McpServices.execute#ScreenAsMcpTool").parameters(transCallParams).call()
                                                            
                                                            // Parse response - transitions return JSON with 'resultList'
                                                            if (transResult?.result?.content?.size() > 0) {
                                                                def responseText = transResult.result.content[0].text
                                                                if (responseText) {
                                                                    ec.logger.info("MCP GetScreenDetails: Transition returned ${responseText.length()} chars")
                                                                    
                                                                    // Parse JSON from response
                                                                    try {
                                                                        def jsonObj = new groovy.json.JsonSlurper().parseText(responseText)
                                                                        
                                                                        if (jsonObj instanceof Map && jsonObj.containsKey("resultList")) {
                                                                            def resultList = jsonObj.resultList
                                                                            if (resultList instanceof List) {
                                                                                def optionsList = []
                                                                                    resultList.each { opt ->
                                                                    if (opt instanceof Map) {
                                                                        def key = opt.geoId ?: opt.value ?: opt.key
                                                                        def label = opt.label ?: opt.value
                                                                        optionsList << [value: key, label: label]
                                                                    }
                                                                                }
                                                                                if (optionsList) {
                                                                                    fieldInfo.options = optionsList
                                                                                    ec.logger.info("MCP GetScreenDetails: Retrieved ${optionsList.size()} options from transition ${transitionName}")
                                                                                }
                                                                            }
                                                                        } else if (jsonObj instanceof Map && jsonObj.containsKey("options")) {
                                                                            fieldInfo.options = jsonObj.options
                                                                        } else if (jsonObj instanceof Map && jsonObj.containsKey("value")) {
                                                                            fieldInfo.options = [[value: jsonObj.value, label: jsonObj.value]]
                                                                        }
                                                                    } catch (Exception parseEx) {
                                                                        ec.logger.warn("MCP GetScreenDetails: Failed to parse transition response: ${parseEx.message}")
                                                                        fieldInfo.optionsError = "Failed to parse options response"
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            fieldInfo.optionsError = "No service or transition specified in dynamic-options"
                                                        }
                                                    } catch (Exception e) {
                                                        ec.logger.warn("MCP GetScreenDetails: Failed to get options for field ${field.name}: ${e.message}")
                                                        fieldInfo.optionsError = "Failed to load options: ${e.message}"
                                                    }
                                                }
                                                
                                                if (!skipField) {
                                                    allFields[field.name] = fieldInfo
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        ec.logger.info("MCP GetScreenDetails: Extracted ${allFields.size()} fields")
                        
                        // Return specific field or all fields
                        if (fieldName) {
                            def specificField = allFields[fieldName]
                            if (specificField) {
                                result.fields[fieldName] = specificField
                            } else {
                                result.error = "Field not found: ${fieldName}"
                            }
                        } else {
                            result.fields = allFields.collectEntries { k, v -> [name: k, *:v] }
                        }
                    } else {
                        ec.logger.warn("MCP GetScreenDetails: No formMetadata found in semantic state")
                        result.error = "No form data available"
                    }
                } else {
                    result.error = "Invalid response from ScreenAsMcpTool"
                }
            } else {
                result.error = "No content returned from ScreenAsMcpTool"
            }
        } catch (Exception e) {
            ec.logger.error("MCP GetScreenDetails: Error: ${e.getClass().simpleName}: ${e.message}")
            result.error = "Screen resolution failed: ${e.message}"
        }
        
        return result
    }
}
