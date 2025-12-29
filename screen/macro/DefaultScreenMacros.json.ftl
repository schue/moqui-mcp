<#--
     Moqui MCP JSON Macros
     Renders screens in structured JSON format for LLM consumption.
 -->

<#include "DefaultScreenMacros.any.ftl"/>

<#macro @element></#macro>

<#macro screen>{"screen": {<#recurse>}}</#macro>

<#macro widgets>
    "widgets": [<#recurse>]
</#macro>

<#macro "fail-widgets"><#recurse></#macro>

<#-- ================ Subscreens ================ -->
<#macro "subscreens-menu"></#macro>
<#macro "subscreens-active">{"type": "subscreens-active", "content": ${sri.renderSubscreen()}}</#macro>
<#macro "subscreens-panel">{"type": "subscreens-panel", "content": ${sri.renderSubscreen()}}</#macro>

<#-- ================ Section ================ -->
<#macro section>{"type": "section", "name": ${(.node["@name"]!"")?json_string}, "content": ${sri.renderSection(.node["@name"])}}</#macro>
<#macro "section-iterate">${sri.renderSection(.node["@name"])}</#macro>
<#macro "section-include">${sri.renderSectionInclude(.node)}</#macro>

<#-- ================ Containers ================ -->
<#macro container>
    <#assign children = []>
    <#list .node?children as child>
        <#assign rendered><#recurse child></#assign>
        <#if rendered?has_content && !(rendered?starts_with("{\"widgets\""))>
            <#assign children = children + [rendered]>
        </#if>
    </#list>
    {"type": "container", "children": [${children?join(",")}]}
</#macro>

<#macro "container-box">
    {"type": "container-box"<#if .node["box-header"]?has_content>, "header": ${.node["box-header"][0]["@label"]!?json_string}</#if><#if .node["box-body"]?has_content>, "body": ${.node["box-body"][0]["@label"]!?json_string}</#if>}
</#macro>

<#macro "container-row"><#list .node["row-col"] as rowColNode><#recurse rowColNode></#list></#macro>

<#macro "container-panel">
    {"type": "container-panel"<#if .node["panel-header"]?has_content>, "header": ${.node["panel-header"][0]["@label"]!?json_string}</#if>}
</#macro>

<#macro "container-dialog">
    {"type": "container-dialog", "buttonText": ${ec.resource.expand(.node["@button-text"], "")?json_string}}
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
            <#assign linkText = sri.getFieldEntityValue(linkNode)!""?string>
        </#if>
        <#if !(linkText?has_content) && .node?parent?node_name?ends_with("-field")>
            <#assign linkText = sri.getFieldValueString(.node?parent?parent)!>
        </#if>

        <#-- Convert path to dot notation for moqui_render_screen -->
        <#assign fullPath = urlInstance.sui.fullPathNameList![]>
        <#assign dotPath = "">
        <#list fullPath as pathPart><#assign dotPath = dotPath + (dotPath?has_content)?then(".", "") + pathPart></#list>

        <#assign paramStr = urlInstance.getParameterString()!"">
        <#if paramStr?has_content><#assign dotPath = dotPath + "?" + paramStr></#if>

        {"type": "link", "text": ${linkText?json_string}, "path": ${dotPath?json_string}}
    </#if>
</#macro>

<#macro image>{"type": "image", "alt": ${(.node["@alt"]!"")?json_string}, "url": ${(.node["@url"]!"")?json_string}}</#macro>

<#macro label>
    <#assign text = ec.resource.expand(.node["@text"], "")>
    <#assign type = .node["@type"]!"span">
    {"type": "label", "text": ${text?json_string}, "labelType": ${type?json_string}}
</#macro>

<#-- ======================= Form ========================= -->
<#macro "form-single">
    <#assign formNode = sri.getFormNode(.node["@name"])>
    <#assign mapName = formNode["@map"]!"fieldValues">

    <#assign fields = []>
    <#t>${sri.pushSingleFormMapContext(mapName)}
    <#list formNode["field"] as fieldNode>
        <#assign fieldSubNode = "">
        <#list fieldNode["conditional-field"] as csf><#if ec.resource.condition(csf["@condition"], "")><#assign fieldSubNode = csf><#break></#if></#list>
        <#if !(fieldSubNode?has_content)><#assign fieldSubNode = fieldNode["default-field"][0]!></#if>
        <#if fieldSubNode?has_content && !(fieldSubNode["ignored"]?has_content) && !(fieldSubNode["hidden"]?has_content) && !(fieldSubNode["submit"]?has_content) && fieldSubNode?parent["@hide"]! != "true">
            <#assign fieldValue = ec.context.get(fieldSubNode?parent["@name"])!"">
            <#if fieldValue?has_content>
                <#assign fieldInfo = {"name": (fieldSubNode?parent["@name"]!"")?json_string, "value": (fieldValue!?json_string)}>
                <#assign fields = fields + [fieldInfo]>
            </#if>
        </#if>
    </#list>
    <#t>${sri.popContext()}
    {"type": "form-single", "name": ${formNode["@name"]?json_string}, "map": ${mapName?json_string}, "fields": [${fields?join(",")}]}
</#macro>

<#macro "form-list">
    <#assign formInstance = sri.getFormInstance(.node["@name"])>
    <#assign formListInfo = formInstance.makeFormListRenderInfo()>
    <#assign formNode = formListInfo.getFormNode()>
    <#assign formListColumnList = formListInfo.getAllColInfo()>
    <#assign listObject = formListInfo.getListObject(false)!>

    {"type": "form-list", "name": ${.node["@name"]?json_string}}
</#macro>

<#macro formListSubField fieldNode>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.resource.condition(fieldSubNode["@condition"], "")>
            {"type": "field", "name": ${fieldSubNode["@name"]?json_string}}
            <#return>
        </#if>
    </#list>
</#macro>

<#macro formListWidget fieldSubNode>
    <#if fieldSubNode["ignored"]?has_content || fieldSubNode["hidden"]?has_content || fieldSubNode?parent["@hide"]! == "true"><#return></#if>
    <#if fieldSubNode["submit"]?has_content>
        <#assign submitText = sri.getFieldValueString(fieldSubNode)!""?json_string>
        <#assign screenName = sri.getEffectiveScreen().name!""?string>
        <#assign formNodeObj = sri.getFormNode(.node["@name"])!"">
        <#assign formName = formNodeObj["@name"]!?string>
        <#assign fieldName = fieldSubNode["@name"]!""?string>
        {"type": "submit", "text": ${submitText}, "action": "${screenName}.${formName}.${fieldName}"}
    </#if>
    <#recurse fieldSubNode>
</#macro>

<#macro fieldTitle fieldSubNode>
    <#assign titleValue><#if fieldSubNode["@title"]?has_content>${fieldSubNode["@title"]}<#else><#list fieldSubNode?parent["@name"]?split("(?=[A-Z])", "r") as nameWord>${nameWord?cap_first?replace("Id", "ID")}<#if nameWord_has_next> </#if></#list></#if></#assign>
    ${ec.l10n.localize(titleValue)?json_string}
</#macro>

<#-- ================== Form Field Widgets ==================== -->
<#macro "check">
    <#assign options = sri.getFieldOptions(.node)!>
    <#assign currentValue = sri.getFieldValueString(.node)!"">
    {"type": "check", "value": ${(options.get(currentValue)!currentValue)?json_string}}
</#macro>

<#macro "date-find"></#macro>

<#macro "date-time">
    <#assign javaFormat = .node["@format"]!"">
    <#if !(javaFormat?has_content)>
        <#if .node["@type"]! == "time"><#assign javaFormat="HH:mm">
        <#elseif .node["@type"]! == "date"><#assign javaFormat="yyyy-MM-dd">
        <#else><#assign javaFormat="yyyy-MM-dd HH:mm"></#if>
    </#if>
    <#assign fieldValue = sri.getFieldValueString(.node?parent?parent, .node["@default-value"]!"", javaFormat)!"">
    {"type": "date-time", "name": ${(.node["@name"]!"")?json_string}, "format": ${javaFormat?json_string}, "value": ${fieldValue?json_string!"null"}}
</#macro>

<#macro "display">
    <#assign fieldValue = "">
    <#assign dispFieldNode = .node?parent?parent>
     <#if .node["@text"]?has_content>
        <#assign textMap = {}>
        <#if .node["@text-map"]?has_content><#assign textMap = ec.getResource().expression(.node["@text-map"], {})!></#if>
        <#assign fieldValue = ec.getResource().expand(.node["@text"], "", textMap, false)!>
        <#if .node["@currency-unit-field"]?has_content>
            <#assign fieldValue = ec.getL10n().formatCurrency(fieldValue, ec.getResource().expression(.node["@currency-unit-field"], ""))!"">
        </#if>
    <#else>
        <#assign fieldValue = sri.getFieldValueString(.node)!"">
    </#if>
    {"type": "display", "value": ${fieldValue?json_string}}
</#macro>

<#macro "display-entity">
    <#assign entityValue = sri.getFieldEntityValue(.node)!"">
    {"type": "display-entity", "value": ${entityValue?json_string}}
</#macro>

<#macro "drop-down">
    <#assign options = sri.getFieldOptions(.node)!>
    <#assign currentValue = sri.getFieldValueString(.node)!"">
    {"type": "drop-down", "value": ${(options.get(currentValue)!currentValue)?json_string}}
</#macro>

<#macro "text-area">
    <#assign fieldValue = sri.getFieldValueString(.node)!"">
    {"type": "text-area", "value": ${fieldValue?json_string}}
</#macro>

<#macro "text-line">
    <#assign fieldValue = sri.getFieldValueString(.node)!"">
    {"type": "text-line", "value": ${fieldValue?json_string}}
</#macro>

<#macro "text-find">
    <#assign fieldValue = sri.getFieldValueString(.node)!"">
    {"type": "text-find", "value": ${fieldValue?json_string}}
</#macro>

<#macro "submit">
    <#assign text = ec.resource.expand(.node["@text"], "")!"">
    {"type": "submit", "text": ${text?json_string}}
</#macro>

<#macro "password"></#macro>
<#macro "hidden"></#macro>
