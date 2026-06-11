'use strict';

    // ---------- Sidebar ----------
    function restoreSidebarState() {
        const sidebar = document.getElementById('sidebar');
        if (!sidebar) return;

        let savedState = null;
        try {
            savedState = localStorage.getItem(SIDEBAR_STATE_STORAGE_KEY);
        } catch (_) {
        }

        sidebar.classList.toggle('collapsed', savedState === 'closed');
    }

    function saveSidebarState(collapsed) {
        try {
            localStorage.setItem(SIDEBAR_STATE_STORAGE_KEY, collapsed ? 'closed' : 'open');
        } catch (_) {
        }
    }

    function toggleSidebar() {
        const sidebar = document.getElementById('sidebar');
        if (!sidebar) return;
        const collapsed = sidebar.classList.toggle('collapsed');
        saveSidebarState(collapsed);
    }

    function openMobileSidebar() {
        document.getElementById('sidebar').classList.add('mobile-open');
        document.getElementById('mobileOverlay').classList.add('active');
    }

    function closeMobileSidebar() {
        document.getElementById('sidebar').classList.remove('mobile-open');
        document.getElementById('mobileOverlay').classList.remove('active');
    }


// ---- PixivGallery facade ----
window.PixivGallery.sidebar = Object.assign(window.PixivGallery.sidebar || {}, { restoreSidebarState, saveSidebarState, toggleSidebar, openMobileSidebar, closeMobileSidebar });
