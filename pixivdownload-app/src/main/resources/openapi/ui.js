"use strict";

// Scalar stores the reader's explicit theme under colorMode. Apply it before the shell paints.
try {
    const mode = localStorage.getItem("colorMode");
    if (mode === "dark" || mode === "light") document.documentElement.style.colorScheme = mode;
} catch {
    // Storage may be unavailable. The shell follows the system in that case.
}

function documentUrl(client) {
    const url = new URL("/api/openapi.json", location.origin);
    url.searchParams.set("lang", client.lang);
    return url;
}

function allowsProgramKey() {
    return location.protocol === "https:" ||
            (location.protocol === "http:" && ["localhost", "[::1]", "127.0.0.1"].includes(location.hostname));
}

function referenceOptions(client, content) {
    const secure = allowsProgramKey();
    const locale = new Intl.Locale(client.lang).maximize();
    const translations = {};
    for (const [key, value] of Object.entries(client.bundleMap.openapi || {})) {
        if (!key.startsWith("scalar.")) continue;
        const parts = key.slice(7).split(".");
        let group = translations;
        for (const part of parts.slice(0, -1)) group = group[part] ??= {};
        group[parts.at(-1)] = value;
    }
    return {
        content,
        generateTagSlug: tagSlug,
        localization: { locale: locale.language + "-" + locale.region, translations },
        withDefaultFonts: false,
        agent: { disabled: true },
        telemetry: false,
        showDeveloperTools: "never",
        hideClientButton: true,
        hideTestRequestButton: !secure,
        persistAuth: false,
        documentDownloadType: "json",
        async customFetch(input, init) {
            const request = new Request(input instanceof Request ? input : new URL(input, location.href), init);
            const url = new URL(request.url);
            if (url.origin !== location.origin || !url.pathname.startsWith("/api/") || !secure ||
                    ["X-Session-Token", "X-GUI-Token", "Cookie"].some(name => request.headers.has(name)) ||
                    !/^Bearer \S+$/i.test(request.headers.get("Authorization") || "")) {
                throw new Error(client.t("request.blocked"));
            }
            return fetch(request, {credentials: "omit", redirect: "error"});
        }
    };
}

/** A Bearer retry must exclude any cookies from the initial session request. */
async function loadCatalog(client, key) {
    try {
        const init = {credentials: "same-origin", redirect: "error"};
        if (key !== undefined) {
            if (!allowsProgramKey()) return {failure: "request.blocked"};
            if (!/^[\x21-\x7e]+$/.test(key)) return {failure: "page.invalidKey"};
            init.credentials = "omit";
            init.headers = {Authorization: "Bearer " + key};
        }
        const response = await fetch(documentUrl(client), init);
        if (key !== undefined && response.status === 401) return {failure: "page.invalidKey"};
        if (key !== undefined && response.status === 403) return {failure: "page.keyForbidden"};
        if (response.status === 401 || response.status === 403) return {failure: "page.denied"};
        if (!response.ok) return {failure: "page.failed"};
        return {content: await response.json()};
    } catch {
        return {failure: "page.failed"};
    }
}

function showCatalogFailure(client, key) {
    const access = document.getElementById("reference-access");
    access.dataset.admin = "false";
    access.dataset.failed = "true";
    const message = document.getElementById("reference-status");
    message.setAttribute("role", "alert");
    message.textContent = client ? client.t(key) : access.dataset.failure;
}

/** Start the renderer early for a signed-in administrator. The catalog request still checks authorization. */
function loadRenderer() {
    return new Promise(resolve => {
        const script = document.createElement("script");
        script.src = "/openapi/scalar.js";
        script.onload = () => resolve(true);
        script.onerror = () => resolve(false);
        document.head.append(script);
    });
}

// Shared by Scalar's sidebar and the links to operations in other groups.
function tagSlug({name}) {
    return (name || "").slice(0, 255).trim().normalize("NFC").toLowerCase()
            .replace(/[^\p{L}\p{M}\p{N}\s_-]/gu, "").replace(/[\s_-]+/g, "-").replace(/^-|-$/g, "");
}

function mountOperationSections(root, catalog, title) {
    const routes = new Map();
    for (const [path, item] of Object.entries(catalog.paths || {})) {
        for (const [method, operation] of Object.entries(item)) {
            if (!/^(get|post|put|patch|delete|options|head|trace)$/.test(method)) continue;
            const tag = operation.tags?.[0];
            const verb = method.toUpperCase();
            const href = "#" + (tag ? "tag/" + tagSlug({name: tag}) + "/" : "") + verb + path;
            routes.set(verb + " " + path, {href, summary: operation.summary,
                related: operation["x-related-operations"], detailsHeading: operation["x-details-heading"]});
        }
    }

    const detailsSelector = ".operation-layout > .operation-details";
    const headingSelector = ".operation-description > .markdown > h3";
    const sections = detailsSelector + ", " + headingSelector;
    function enhance(element) {
        // Scalar prefixes the DOM id with its document slug; public links omit that prefix.
        const match = element.closest("section[id]")?.id.match(/\/([A-Z]+)(\/.*)$/);
        const route = match && routes.get(match[1] + " " + match[2]);
        if (!route) return;
        if (element.matches(detailsSelector)) {
            appendFooter(element, route.related);
        } else if (element.matches(headingSelector) && element.textContent === route.detailsHeading) {
            foldDetails(element);
        }
    }

    function foldDetails(heading) {
        const panel = document.createElement("details");
        panel.className = "reference-disclosure";
        const summary = document.createElement("summary");
        const body = document.createElement("div");
        // The catalog appends the optional chapter at the end of the operation's Markdown.
        while (heading.nextSibling) body.append(heading.nextSibling);
        heading.replaceWith(panel);
        summary.append(heading);
        panel.append(summary, body);
    }

    function appendFooter(details, related) {
        if (!related?.length) return;
        const responses = details.querySelector(":scope > div > .responses-list--tree");
        const container = responses?.parentElement ?? details;
        if (container.lastElementChild?.classList.contains("reference-related")) return;
        const list = document.createElement("ul");
        for (const route of related) {
            const target = routes.get(route);
            if (!target) continue;
            const link = document.createElement("a");
            link.href = target.href;
            if (target.summary) link.append(target.summary + " ");
            const code = document.createElement("code");
            code.textContent = route;
            link.append(code);
            const item = document.createElement("li");
            item.append(link);
            list.append(item);
        }
        if (!list.childElementCount) return;
        const footer = document.createElement("footer");
        footer.className = "reference-related";
        const heading = document.createElement("h4");
        heading.textContent = title;
        footer.append(heading, list);
        container.append(footer);
    }

    // Scalar mounts operation layouts as their group opens. Only inspect newly mounted subtrees.
    new MutationObserver(records => {
        for (const {target, addedNodes} of records) {
            if (target.closest?.(detailsSelector)) continue;
            for (const node of addedNodes) {
                if (node.nodeType !== Node.ELEMENT_NODE) continue;
                if (node.matches(sections)) enhance(node);
                else node.querySelectorAll(sections).forEach(enhance);
            }
        }
    }).observe(root, {subtree: true, childList: true});
}

/** Scalar unmounts a group's operations when its sidebar disclosure closes. */
function limitExpandedGroups(root) {
    const sidebarItem = "[data-sidebar-id]";
    const expanded = 'button[aria-expanded="true"]';
    const observer = new MutationObserver(records => {
        const sidebar = root.querySelector(".t-doc__sidebar");
        let selected, opened;
        for (const {target, attributeName} of records) {
            if (!sidebar?.contains(target)) continue;
            const group = target.closest(".t-doc__sidebar > ul > [data-sidebar-id]");
            if (!group) continue;
            if (attributeName === "aria-current" && target.matches('a[aria-current="page"]')) {
                const toggle = group.querySelector("button[aria-expanded]");
                if (toggle?.matches(expanded) || (!toggle && target.hash.startsWith("#tag/"))) selected = group;
            }
            if (attributeName === "aria-expanded" && target.matches(expanded)
                    && target.closest(sidebarItem) === group) opened = group;
        }
        // Vue can publish several expansions in one batch when navigation changes quickly.
        const active = selected ?? opened;
        if (!active) return;
        for (const sibling of active.parentElement.children) {
            if (sibling === active || !sibling.matches(sidebarItem)) continue;
            const toggle = sibling.querySelector("button[aria-expanded]");
            if (toggle?.matches(expanded) && toggle.closest(sidebarItem) === sibling) toggle.click();
        }
    });
    observer.observe(root, {subtree: true, attributes: true, attributeFilter: ["aria-expanded", "aria-current"]});
}

async function openReference() {
    const access = document.getElementById("reference-access");
    const renderer = access.dataset.admin === "true" ? loadRenderer() : null;
    const client = await PixivI18n.create({
        namespaces: ["openapi"], lang: new URL(location.href).searchParams.get("lang")
    });
    const language = document.getElementById("reference-language");
    document.documentElement.lang = client.lang;
    language.append(document.createTextNode(client.t("language") + " "));
    const catalogRequest = loadCatalog(client);
    await PixivLangSwitcher.mount({mountPoint: language, i18n: client, variant: "compact", onChange(next) {
        if (next.lang === client.lang) return;
        const url = new URL(location.href);
        url.searchParams.set("lang", next.lang);
        location.replace(url);
    }});
    document.title = client.t("title");
    const catalog = await catalogRequest;
    if (catalog.failure) {
        showCatalogFailure(client, catalog.failure === "page.denied" && !allowsProgramKey()
                ? "request.blocked" : catalog.failure);
        if (catalog.failure === "page.denied" && allowsProgramKey()) {
            const form = document.getElementById("reference-key-form");
            const input = document.getElementById("reference-key");
            const submit = form.querySelector("button");
            form.onsubmit = async event => {
                event.preventDefault();
                if (submit.disabled) return;
                submit.disabled = true;
                const retry = loadCatalog(client, input.value.trim());
                input.value = "";
                const status = document.getElementById("reference-status");
                status.setAttribute("role", "status");
                status.textContent = client.t("page.loading");
                const result = await retry;
                submit.disabled = false;
                if (result.failure) {
                    showCatalogFailure(client, result.failure);
                    input.focus();
                    return;
                }
                form.hidden = true;
                await renderCatalog(client, result.content, access, renderer);
            };
            form.hidden = false;
        }
        return;
    }
    await renderCatalog(client, catalog.content, access, renderer);
}

async function renderCatalog(client, content, access, renderer) {
    access.dataset.admin = "true";
    delete access.dataset.failed;
    document.getElementById("reference-status").textContent = client.t("page.rendering");
    try {
        if (!await (renderer ?? loadRenderer())) {
            showCatalogFailure(client, "page.failed");
            return;
        }
        // Commit the loading frame before Scalar starts processing the catalog on the main thread.
        if (document.visibilityState === "visible") {
            await new Promise(resolve => requestAnimationFrame(() => setTimeout(resolve, 0)));
        }
        const root = document.getElementById("reference-content");
        mountOperationSections(root, content, client.t("related.operations"));
        limitExpandedGroups(root);
        Scalar.createApiReference("#reference-content", {
            ...referenceOptions(client, content),
            onLoaded() {
                access.remove();
                document.documentElement.style.removeProperty("color-scheme");
            }
        });
    } catch {
        showCatalogFailure(client, "page.failed");
    }
}

document.addEventListener("DOMContentLoaded", () => openReference().catch(() => showCatalogFailure(null, "page.failed")), {once: true});
