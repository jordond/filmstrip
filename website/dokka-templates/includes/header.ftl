<#import "source_set_selector.ftl" as source_set_selector>
<#-- Dokka's own header with the site's OSD mark, version menu and text nav. The reference is dark only, so the theme
toggle stays hidden. Dokka's script needs the button to exist and throws without it. -->
<#macro display>
    <header class="navigation theme-dark" id="navigation-wrapper" role="banner">
        <@template_cmd name="pathToRoot">
        <a class="library-name--link osd-mark" href="${pathToRoot}../" tabindex="1" aria-label="filmstrip home">
            <svg width="18" height="20" viewBox="0 0 18 20" aria-hidden="true">
                <path d="M2 2 L16 10 L2 18 Z" fill="currentColor"></path>
            </svg>
            <span>PLAY</span>
            <span class="osd-speed">SP</span>
        </a></@template_cmd>
        <button class="navigation-controls--btn navigation-controls--btn_toc ui-kit_mobile-only" id="toc-toggle"
                type="button">Toggle table of contents
        </button>
        <div class="navigation-controls--break ui-kit_mobile-only"></div>
        <div class="library-version" id="library-version">
            <select class="version-menu" data-version-menu aria-label="Docs version" hidden></select>
        </div>
        <div class="navigation-controls">
            <@source_set_selector.display/>
            <@template_cmd name="pathToRoot">
            <nav class="osd-nav" aria-label="Site">
                <a href="${pathToRoot}../start/installation/">DOCS</a>
                <a href="${pathToRoot}index.html">API</a>
                <a href="https://github.com/jordond/filmstrip">GITHUB</a>
            </nav></@template_cmd>
            <button class="navigation-controls--btn navigation-controls--btn_theme" id="theme-toggle-button"
                    type="button" hidden>Switch theme
            </button>
            <div class="navigation-controls--btn navigation-controls--btn_search" id="searchBar" role="button">Search in
                API
            </div>
        </div>
    </header>
</#macro>
