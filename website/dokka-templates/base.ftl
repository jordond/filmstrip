<#import "includes/page_metadata.ftl" as page_metadata>
<#import "includes/header.ftl" as header>
<#import "includes/footer.ftl" as footer>
<#-- Dokka's own base template, dark from the first paint and without the script that reads a stored theme. -->
<!DOCTYPE html>
<html class="no-js theme-dark" lang="en">
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1" charset="UTF-8">
    <@page_metadata.display/>
    <@template_cmd name="pathToRoot">
        <script>var pathToRoot = "${pathToRoot}";</script></@template_cmd>
    <script>document.documentElement.classList.replace("no-js", "js");</script>
    <@resources/>
</head>
<body>
<div class="root">
    <@header.display/>
    <div id="container">
        <nav id="leftColumn" class="sidebar" data-item-type="SECTION" data-item-config='{"defaultSize": 280, "minSize": 200, "maxSize": 400}'>
            <a class="toc--skip-link" href="#main">Skip to content</a>
            <div class="dropdown theme-dark_mobile" data-role="dropdown" id="toc-dropdown">
                <ul role="listbox" id="toc-listbox" class="dropdown--list dropdown--list_toc-list"
                    data-role="dropdown-listbox" aria-label="Table of contents">
                    <div class="dropdown--header">
                            <span>
                                <@template_cmd name="projectName">
                                    ${projectName}
                                </@template_cmd>
                            </span>
                        <button class="button" data-role="dropdown-toggle" aria-label="Close table of contents">
                            <i class="ui-kit-icon ui-kit-icon_cross"></i>
                        </button>
                    </div>
                    <div class="sidebar--inner" id="sideMenu"></div>
                </ul>
                <div class="dropdown--overlay"></div>
            </div>
        </nav>
        <div id="resizer" class="resizer" data-item-type="BAR"></div>
        <div id="main" data-item-type="SECTION" role="main">
            <@content/>
            <@footer.display/>
        </div>
    </div>
</div>
</body>
</html>
