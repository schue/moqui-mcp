<#--
    Moqui MCP Optimized Macros
    Renders screens in Markdown format optimized for LLM consumption.
-->

<#include "DefaultScreenMacros.any.ftl"/>

<#macro @element></#macro>

<#macro screen><#recurse></#macro>

<#macro widgets>
    <#recurse>
</#macro>

<#macro "fail-widgets"><#recurse></#macro>

<#-- ================ Subscreens ================ -->
<#macro "subscreens-menu"></#macro>
<#macro "subscreens-active">${sri.renderSubscreen()}</#macro>
<#macro "subscreens-panel">${sri.renderSubscreen()}</#macro>

<#-- ================ Section ================ -->
<#macro section>${sri.renderSection(.node["@name"])}</#macro>
<#macro "section-iterate">${sri.renderSection(.node["@name"])}</#macro>
<#macro "section-include">${sri.renderSectionInclude(.node)}</#macro>

<#-- ================ Containers ================ -->
<#macro container>
<#recurse>
</#macro>

<#macro "container-box">
<#if .node["box-header"]?has_content>### <#recurse .node["box-header"][0]></#if>
<#if .node["box-body"]?has_content><#recurse .node["box-body"][0]></#if>
<#if .node["box-body-nopad"]?has_content><#recurse .node["box-body-nopad"][0]></#if>
</#macro>

<#macro "container-row"><#list .node["row-col"] as rowColNode><#recurse rowColNode></#list></#macro>

<#macro "container-panel">
<#if .node["panel-header"]?has_content>### <#recurse .node["panel-header"][0]></#if>
<#if .node["panel-left"]?has_content><#recurse .node["panel-left"][0]></#if>
<#recurse .node["panel-center"][0]>
<#if .node["panel-right"]?has_content><#recurse .node["panel-right"][0]></#if>
<#if .node["panel-footer"]?has_content><#recurse .node["panel-footer"][0]></#if>
</#macro>

<#macro "container-dialog">
[Button: ${ec.resource.expand(.node["@button-text"], "")}]
</#macro>

<#-- ================== Standalone Fields ==================== -->
<#macro link>
    <#assign linkNode = .node>
    <#if linkNode["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(linkNode["@condition"], "")><#else><#assign conditionResult = true></#if>
    <#if conditionResult>
        <#assign urlInstance = sri.makeUrlByType(linkNode["@url"]!"", linkNode["@url-type"]!"transition", linkNode, "true")>
        <#assign linkText = "">
        <#if linkNode["@text"]?has_content>
            <#assign linkText = ec.getResource().expand(linkNode["@text"], "")>
        <#elseif linkNode["@entity-name"]?has_content>
            <#assign linkText = sri.getFieldEntityValue(linkNode)>
        </#if>
        <#if !linkText?has_content && .node?parent?node_name?ends_with("-field")>
             <#assign linkText = sri.getFieldValueString(.node?parent?parent)>
        </#if>

        <#-- Convert path to dot notation for moqui_render_screen -->
        <#assign fullPath = urlInstance.sui.fullPathNameList![]>
        <#assign dotPath = "">
        <#list fullPath as pathPart><#assign dotPath = dotPath + (dotPath?has_content)?then(".", "") + pathPart></#list>
        
        <#assign paramStr = urlInstance.getParameterString()>
        <#if paramStr?has_content><#assign dotPath = dotPath + "?" + paramStr></#if>
        
        [${linkText}](${dotPath})<#t>
    </#if>
</#macro>

<#macro image>![${.node["@alt"]!""}](${(.node["@url"]!"")})</#macro>

<#macro label>
<#assign text = ec.resource.expand(.node["@text"], "")>
<#assign type = .node["@type"]!"span">
<#if type == "h1"># ${text}
<#elseif type == "h2">## ${text}
<#elseif type == "h3">### ${text}
<#elseif type == "p">${text}
<#else>${text}</#if>
</#macro>

<#-- ======================= Form ========================= -->
<#macro "form-single">
    <#assign formNode = sri.getFormNode(.node["@name"])>
    <#assign mapName = formNode["@map"]!"fieldValues">
    <#t>${sri.pushSingleFormMapContext(mapName)}
    <#list formNode["field"] as fieldNode>
        <#assign fieldSubNode = "">
        <#list fieldNode["conditional-field"] as csf><#if ec.resource.condition(csf["@condition"], "")><#assign fieldSubNode = csf><#break></#if></#list>
        <#if !fieldSubNode?has_content><#assign fieldSubNode = fieldNode["default-field"][0]!></#if>
        <#if fieldSubNode?has_content && !fieldSubNode["ignored"]?has_content && !fieldSubNode["hidden"]?has_content && !fieldSubNode["submit"]?has_content && fieldSubNode?parent["@hide"]! != "true">
            <#assign title><@fieldTitle fieldSubNode/></#assign>
            * **${title}**: <#recurse fieldSubNode>
        </#if>
    </#list>
    <#t>${sri.popContext()}
</#macro>

<#macro "form-list">
    <#assign formInstance = sri.getFormInstance(.node["@name"])>
    <#assign formListInfo = formInstance.makeFormListRenderInfo()>
    <#assign formNode = formListInfo.getFormNode()>
    <#assign formListColumnList = formListInfo.getAllColInfo()>
    <#assign listObject = formListInfo.getListObject(false)!>
    
    <#-- Header Row -->
    <#list formListColumnList as columnFieldList>
        <#assign fieldNode = columnFieldList[0]>
        <#assign fieldSubNode = fieldNode["header-field"][0]!fieldNode["default-field"][0]!fieldNode["conditional-field"][0]!>
        <#t>| <@fieldTitle fieldSubNode/><#t>
    </#list>
    |
    <#list formListColumnList as columnFieldList>| --- </#list>|
    <#-- Data Rows -->
    <#list listObject as listEntry>
        <#t>${sri.startFormListRow(formListInfo, listEntry, listEntry_index, listEntry_has_next)}
        <#list formListColumnList as columnFieldList>
            <#t>| <#list columnFieldList as fieldNode><@formListSubField fieldNode/><#if fieldNode_has_next> </#if></#list><#t>
        </#list>
        |
        <#t>${sri.endFormListRow()}
    </#list>
    <#t>${sri.safeCloseList(listObject)}
</#macro>

<#macro formListSubField fieldNode>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.resource.condition(fieldSubNode["@condition"], "")>
            <#t><@formListWidget fieldSubNode/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <#t><@formListWidget fieldNode["default-field"][0]/>
    </#if>
</#macro>

<#macro formListWidget fieldSubNode>
    <#if fieldSubNode["ignored"]?has_content || fieldSubNode["hidden"]?has_content || fieldSubNode?parent["@hide"]! == "true"><#return></#if>
    <#if fieldSubNode["submit"]?has_content>
        <#assign submitText = sri.getFieldValueString(fieldSubNode)!>
        <#assign screenName = sri.getEffectiveScreen().name!?string>
        <#assign formName = .node["@name"]!?string>
        <#assign fieldName = fieldSubNode["@name"]!?string>
        [${submitText}](#${screenName}.${formName}.${fieldName})
    </#if>
    <#recurse fieldSubNode>
</#macro>

<#macro fieldTitle fieldSubNode>
    <#assign titleValue><#if fieldSubNode["@title"]?has_content>${fieldSubNode["@title"]}<#else><#list fieldSubNode?parent["@name"]?split("(?=[A-Z])", "r") as nameWord>${nameWord?cap_first?replace("Id", "ID")}<#if nameWord_has_next> </#if></#list></#if></#assign>
    <#t>${ec.l10n.localize(titleValue)}
</#macro>

<#-- ================== Form Field Widgets ==================== -->
<#macro "check">
    <#assign options = sri.getFieldOptions(.node)!>
    <#assign currentValue = sri.getFieldValueString(.node)>
    <#t>${(options.get(currentValue))!(currentValue)}
</#macro>

<#macro "date-find"></#macro>
<#macro "date-time">
    <#assign javaFormat = .node["@format"]!>
    <#if !javaFormat?has_content>
        <#if .node["@type"]! == "time"><#assign javaFormat="HH:mm">
        <#elseif .node["@type"]! == "date"><#assign javaFormat="yyyy-MM-dd">
        <#else><#assign javaFormat="yyyy-MM-dd HH:mm"></#if>
    </#if>
    <#assign fieldValue = sri.getFieldValueString(.node?parent?parent, .node["@default-value"]!"", javaFormat)>
    <#t>${fieldValue}
</#macro>

<#macro "display">
    <#assign fieldValue = "">
    <#assign dispFieldNode = .node?parent?parent>
     <#if .node["@text"]?has_content>
        <#assign textMap = {}>
        <#if .node["@text-map"]?has_content><#assign textMap = ec.getResource().expression(.node["@text-map"], {})!></#if>
        <#assign fieldValue = ec.getResource().expand(.node["@text"], "", textMap, false)>
        <#if .node["@currency-unit-field"]?has_content>
            <#assign fieldValue = ec.getL10n().formatCurrency(fieldValue, ec.getResource().expression(.node["@currency-unit-field"], ""))>
        </#if>
    <#else>
        <#assign fieldValue = sri.getFieldValueString(.node)>
    </#if>
    <#t>${fieldValue}
</#macro>

<#macro "display-entity">
    <#t>${sri.getFieldEntityValue(.node)}
</#macro>

<#macro "drop-down">
    <#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValueString(.node)>
    <#t>${(options.get(currentValue))!(currentValue)}
</#macro>

<#macro "text-area"><#t>${sri.getFieldValueString(.node)}</#macro>
<#macro "text-line"><#t>${sri.getFieldValueString(.node)}</#macro>
<#macro "text-find"><#t>${sri.getFieldValueString(.node)}</#macro>
<#macro "submit"></#macro>
<#macro "password"></#macro>
<#macro "hidden"></#macro>
