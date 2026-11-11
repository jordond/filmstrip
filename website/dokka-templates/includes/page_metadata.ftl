<#-- Dokka's own page metadata with the site's icon in place of the Dokka logo. -->
<#macro display>
    <title>${pageName}</title>
    <@template_cmd name="pathToRoot">
        <link href="${pathToRoot}../favicon.svg" rel="icon" type="image/svg+xml"></@template_cmd>
</#macro>
