<#--
    Moqui JSON Optimized Macros
    Renders screens in structured JSON format.
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
<#macro section>{"type": "section", "name": "${.node["@name"]}", "content": ${sri.renderSection(.node["@name"])}}</#macro>

<#-- ================ Containers ================ -->
<#macro container>
{"type": "container", "id": "${.node["@id"]!""}", "style": "${.node["@style"]!""}", "children": [<#recurse>]}
</#macro>

<#macro label>
{"type": "label", "text": "${ec.resource.expand(.node["@text"], "")?json_string}"}
</#macro>

<#macro link>
{"type": "link", "text": "${ec.resource.expand(.node["@text"]!"", "")?json_string}", "url": "${.node["@url"]!""}"}
</#macro>
