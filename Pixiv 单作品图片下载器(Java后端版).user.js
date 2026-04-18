// ==UserScript==
// @name         Pixiv作品图片下载器（Java后端版）
// @namespace    http://tampermonkey.net/
// @version      2.0.4
// @description  通过Java后端服务下载 Pixiv 单个作品图片
// @author       Rewritten by ChatGPT,Claude,Sywyar
// @match        https://www.pixiv.net/*
// @grant        GM_registerMenuCommand
// @grant        GM_xmlhttpRequest
// @grant        GM_setValue
// @grant        GM_getValue
// @connect      i.pximg.net
// @connect      www.pixiv.net
// @connect      localhost
// @connect      YOUR_SERVER_HOST
// @run-at       document-start
// ==/UserScript==

(function () {
    'use strict';

    // 配置后端服务地址
    const KEY_SERVER_URL = 'pixiv_server_base';
    let serverBase = GM_getValue(KEY_SERVER_URL, 'http://localhost:6999').replace(/\/$/, '');

    const KEY_USER_UUID = 'pixiv_user_uuid';
    const KEY_BOOKMARK_AFTER_DL = 'pixiv_bookmark_after_dl';
    const KEY_SKIP_HISTORY = 'pixiv_single_skip_history';
    const KEY_VERIFY_HISTORY_FILES = 'pixiv_single_verify_history_files';
    const VERIFY_HISTORY_FILES_TOOLTIP = '通过检查记录的目录是否存在、文件夹是否为空、文件夹中的文件是否包含图片来判断是否有效，如果无效则会重新下载';

    // 动态 URL 计算
    const getBackendURL = () => serverBase + '/api/download/pixiv';
    const getQuotaInitURL = () => serverBase + '/api/quota/init';
    const getArchiveStatusBase = () => serverBase + '/api/archive/status';
    const getArchiveDownloadBase = () => serverBase + '/api/archive/download';
    const getDownloadedCheckBase = () => serverBase + '/api/downloaded';

    let userUUID = GM_getValue(KEY_USER_UUID, null);
    let quotaInfo = { enabled: false, artworksUsed: 0, maxArtworks: 50, resetSeconds: 0 };

    // 首次启动提示（只显示一次）
    function checkExternalServerNotice() {
        const key = 'pixiv_connect_notice_shown';
        if (GM_getValue(key, false)) return;
        GM_setValue(key, true);
        alert(
            'Pixiv 下载脚本初始化提示\n\n' +
            '如果您使用外部服务器（非 localhost），需将三个脚本头部的：\n' +
            '  // @connect      YOUR_SERVER_HOST\n' +
            '替换为实际的服务器 IP 或域名，例如：\n' +
            '  // @connect      192.168.1.100\n\n' +
            '修改路径：Tampermonkey 管理面板 → 对应脚本 → 编辑 → 保存\n\n' +
            '或者直接通过网页端下载作品（无需脚本）：\n' +
            serverBase + '/login.html\n\n' +
            '（此提示只显示一次）'
        );
    }

    // 检查登录状态（solo 模式未登录返回 401）
    function checkLoginStatus() {
        return new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: serverBase + '/api/download/status/0',
                timeout: 5000,
                onload: (res) => {
                    if (res.status === 401) { handleUnauthorized(); resolve(false); }
                    else resolve(true);
                },
                onerror: () => resolve(true),
                ontimeout: () => resolve(true)
            });
        });
    }

    // 处理 solo 模式未登录（401）
    function handleUnauthorized() {
        alert('后端服务需要登录验证，即将为您打开登录页面...');
        window.open(serverBase + '/login.html', '_blank');
    }

    // 等待页面加载完成
    function waitForPageLoad() {
        return new Promise(resolve => {
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', resolve);
            } else {
                resolve();
            }
        });
    }

    // 获取作品ID
    function getArtworkId() {
        const url = window.location.href;
        const match = url.match(/artworks\/(\d+)/);
        return match ? match[1] : null;
    }

    // 获取图片URL
    async function getImageUrls(artworkId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/illust/${artworkId}/pages`,
                headers: {
                    'Referer': 'https://www.pixiv.net/'
                },
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        if (data.error) {
                            reject(new Error(data.message));
                            return;
                        }

                        const urls = data.body.map(page => page.urls.original);
                        resolve(urls);
                    } catch (e) {
                        reject(e);
                    }
                },
                onerror: function (error) {
                    reject(error);
                }
            });
        });
    }

    // 获取作品元数据
    async function getArtworkMeta(artworkId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/illust/${artworkId}`,
                headers: { 'Referer': 'https://www.pixiv.net/' },
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        if (data.error) reject(new Error(data.message));
                        else resolve(data.body);
                    } catch (e) { reject(e); }
                },
                onerror: reject
            });
        });
    }

    // 获取动图元数据
    async function getUgoiraMeta(artworkId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/illust/${artworkId}/ugoira_meta`,
                headers: { 'Referer': 'https://www.pixiv.net/' },
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        if (data.error) reject(new Error(data.message));
                        else resolve(data.body);
                    } catch (e) { reject(e); }
                },
                onerror: reject
            });
        });
    }

    // 初始化配额
    function initQuota() {
        const headers = { 'Content-Type': 'application/json' };
        if (userUUID) headers['X-User-UUID'] = userUUID;
        return new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'POST',
                url: getQuotaInitURL(),
                headers,
                onload: (res) => {
                    try {
                        const data = JSON.parse(res.responseText);
                        if (data.uuid) {
                            userUUID = data.uuid;
                            GM_setValue(KEY_USER_UUID, userUUID);
                        }
                        resolve(data);
                    } catch { resolve({}); }
                },
                onerror: () => resolve({}),
                ontimeout: () => resolve({})
            });
        });
    }

    // 发送下载请求到后端
    async function sendDownloadRequest(artworkId, title, imageUrls, other) {
        return new Promise((resolve, reject) => {
            const requestData = {
                artworkId: parseInt(artworkId),
                title: title,
                imageUrls: imageUrls,
                referer: 'https://www.pixiv.net/',
                cookie: document.cookie,
                other: other || {}
            };
            const headers = { 'Content-Type': 'application/json' };
            if (userUUID) headers['X-User-UUID'] = userUUID;

            GM_xmlhttpRequest({
                method: 'POST',
                url: getBackendURL(),
                headers,
                data: JSON.stringify(requestData),
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        if (response.status === 401) {
                            handleUnauthorized();
                            reject(new Error('需要登录'));
                        } else if (response.status === 429 && data.quotaExceeded) {
                            const err = new Error('quota_exceeded');
                            err.quotaData = data;
                            reject(err);
                        } else if (response.status === 200 && data.success) {
                            resolve(data);
                        } else {
                            reject(new Error(data.message || '下载请求失败'));
                        }
                    } catch (e) {
                        reject(e);
                    }
                },
                onerror: function (error) {
                    reject(error);
                }
            });
        });
    }

    // 检查后端服务状态
    async function checkBackendStatus() {
        return new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: serverBase + '/api/download/status',
                timeout: 3000,
                onload: function (response) {
                    resolve(response.status === 200);
                },
                onerror: function () {
                    resolve(false);
                },
                ontimeout: function () {
                    resolve(false);
                }
            });
        });
    }

    // 下载图片
    async function checkDownloaded(artworkId, verifyFiles = false) {
        return new Promise((resolve) => {
            const query = verifyFiles ? '?verifyFiles=true' : '';
            GM_xmlhttpRequest({
                method: 'GET',
                url: `${getDownloadedCheckBase()}/${artworkId}${query}`,
                timeout: 5000,
                onload: function (response) {
                    if (response.status === 401) {
                        handleUnauthorized();
                        resolve(false);
                        return;
                    }
                    if (response.status !== 200) {
                        resolve(false);
                        return;
                    }
                    try {
                        const data = JSON.parse(response.responseText);
                        resolve(!!data.artworkId);
                    } catch (e) {
                        resolve(false);
                    }
                },
                onerror: function () {
                    resolve(false);
                },
                ontimeout: function () {
                    resolve(false);
                }
            });
        });
    }

    async function downloadImages() {
        const artworkId = getArtworkId();
        if (!artworkId) {
            alert('无法获取作品ID');
            return;
        }

        // 检查后端服务是否可用
        const isBackendAvailable = await checkBackendStatus();
        if (!isBackendAvailable) {
            alert(`后端下载服务未启动！\n请确保Java Spring程序正在 ${serverBase} 运行`);
            return;
        }

        // 检查是否已下载
        const skipHistory = GM_getValue(KEY_SKIP_HISTORY, false);
        const verifyHistoryFiles = skipHistory && GM_getValue(KEY_VERIFY_HISTORY_FILES, false);
        if (skipHistory) {
            const alreadyDownloaded = await checkDownloaded(artworkId, verifyHistoryFiles);
            if (alreadyDownloaded) {
                alert(`作品 ${artworkId} 已存在于下载历史中，已跳过本次下载。`);
                return;
            }
        }

        try {
            // 显示下载开始提示
            alert(`开始下载作品 ${artworkId} 的图片...`);

            // 获取作品元数据（用于标题和类型检测）
            const meta = await getArtworkMeta(artworkId);
            const title = (meta && meta.illustTitle) ? meta.illustTitle : `Artwork ${artworkId}`;
            const parsedAuthorId = Number.parseInt(String(meta?.userId || ''), 10);
            const authorId = Number.isFinite(parsedAuthorId) ? parsedAuthorId : null;
            const authorName = meta?.userName || null;
            const isR18 = Number(meta?.xRestrict ?? 0) > 0;

            let imageUrls;
            const bookmark = GM_getValue(KEY_BOOKMARK_AFTER_DL, false);
            let other = { bookmark, authorId, authorName, isR18 };

            if (meta && meta.illustType === 2) {
                // 动图作品：获取ugoira元数据，下载ZIP并在后端合成WebP
                const ugoiraMeta = await getUgoiraMeta(artworkId);
                const zipSrc = ugoiraMeta.originalSrc || ugoiraMeta.src;
                imageUrls = [zipSrc];
                other = {
                    isUgoira: true,
                    ugoiraZipUrl: zipSrc,
                    ugoiraDelays: ugoiraMeta.frames.map(f => f.delay),
                    bookmark,
                    authorId,
                    authorName,
                    isR18
                };
            } else if (meta && meta.pageCount === 1 && meta.urls && meta.urls.original) {
                // 单页插画：meta 已携带 original URL，无需再调用 /pages
                imageUrls = [meta.urls.original];
            } else {
                imageUrls = await getImageUrls(artworkId);
            }

            if (imageUrls.length === 0) {
                alert('未找到图片');
                return;
            }

            // 发送下载请求到后端
            const response = await sendDownloadRequest(artworkId, title, imageUrls, other);

            // 刷新配额显示
            if (quotaInfo.enabled) {
                quotaInfo.artworksUsed = Math.min(quotaInfo.maxArtworks, quotaInfo.artworksUsed + 1);
            }
            updateDownloadUI();

            const typeHint = other.isUgoira ? '动图（将合成为WebP）' : `图片数量: ${imageUrls.length}张`;
            alert(`下载任务已提交到后端处理！\n${typeHint}\n${response.message}`);

        } catch (error) {
            if (error.message === 'quota_exceeded' && error.quotaData) {
                console.warn('已达到下载限额');
                showQuotaExceededUI(error.quotaData);
            } else {
                console.error('下载失败:', error);
                alert('下载失败: ' + error.message);
            }
        }
    }

    // 创建下载UI
    function createDownloadUI() {
        // 移除已存在的UI
        const existingUI = document.getElementById('pixiv-downloader-ui');
        if (existingUI) {
            existingUI.remove();
        }

        const artworkId = getArtworkId();
        if (!artworkId) return; // 如果不是作品页面，不显示UI

        const skipHistoryEnabled = GM_getValue(KEY_SKIP_HISTORY, false);
        const verifyHistoryFilesEnabled = GM_getValue(KEY_VERIFY_HISTORY_FILES, false);

        const container = document.createElement('div');
        container.id = 'pixiv-downloader-ui';
        container.style.cssText = `
            position: fixed;
            top: 100px;
            right: 20px;
            z-index: 10000;
            background: white;
            border: 2px solid #0096fa;
            border-radius: 8px;
            padding: 10px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.2);
            min-width: 260px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        `;

        const titleDiv = document.createElement('div');
        titleDiv.innerHTML = 'Pixiv下载器 (Java后端)';
        titleDiv.style.cssText = `
            font-weight: bold;
            margin-bottom: 5px;
            color: #333;
            text-align: center;
            font-size: 16px;
            border-bottom: 1px solid #eee;
            padding-bottom: 5px;
        `;

        const statusDiv = document.createElement('div');
        statusDiv.innerHTML = '⬇️ 可下载此作品';
        statusDiv.style.cssText = `
            font-weight: bold;
            margin-bottom: 8px;
            color: #0096fa;
            text-align: center;
            font-size: 14px;
        `;

        const button = document.createElement('button');
        button.innerHTML = '📥 通过后端下载';
        button.style.cssText = `
            width: 100%;
            background: #0096fa;
            color: white;
            border: none;
            padding: 8px 12px;
            border-radius: 5px;
            cursor: pointer;
            font-size: 14px;
            font-weight: bold;
            transition: background 0.2s;
            margin-bottom: 5px;
        `;
        button.addEventListener('mouseenter', () => {
            button.style.background = '#007acc';
        });
        button.addEventListener('mouseleave', () => {
            button.style.background = '#0096fa';
        });
        button.addEventListener('click', downloadImages);

        // 下载后收藏开关
        const bookmarkRow = document.createElement('div');
        bookmarkRow.style.cssText = 'display:flex;align-items:center;gap:6px;margin-bottom:6px;font-size:12px;color:#555;';
        const bookmarkChk = document.createElement('input');
        bookmarkChk.type = 'checkbox';
        bookmarkChk.id = 'pixiv-dl-bookmark';
        bookmarkChk.checked = GM_getValue(KEY_BOOKMARK_AFTER_DL, false);
        bookmarkChk.addEventListener('change', () => GM_setValue(KEY_BOOKMARK_AFTER_DL, bookmarkChk.checked));
        const bookmarkLabel = document.createElement('label');
        bookmarkLabel.htmlFor = 'pixiv-dl-bookmark';
        bookmarkLabel.textContent = '下载后自动收藏';
        bookmarkLabel.style.cursor = 'pointer';
        bookmarkRow.appendChild(bookmarkChk);
        bookmarkRow.appendChild(bookmarkLabel);

        const skipHistoryRow = document.createElement('div');
        skipHistoryRow.style.cssText = 'display:flex;align-items:center;gap:6px;margin-bottom:6px;font-size:12px;color:#555;';
        const skipHistoryChk = document.createElement('input');
        skipHistoryChk.type = 'checkbox';
        skipHistoryChk.id = 'pixiv-dl-skip-history';
        skipHistoryChk.checked = skipHistoryEnabled;
        const skipHistoryLabel = document.createElement('label');
        skipHistoryLabel.htmlFor = 'pixiv-dl-skip-history';
        skipHistoryLabel.textContent = '跳过历史下载';
        skipHistoryLabel.style.cursor = 'pointer';
        skipHistoryRow.appendChild(skipHistoryChk);
        skipHistoryRow.appendChild(skipHistoryLabel);

        const verifyHistoryFilesRow = document.createElement('div');
        verifyHistoryFilesRow.style.cssText = `display:${skipHistoryEnabled ? 'flex' : 'none'};align-items:center;gap:6px;margin-bottom:6px;font-size:12px;color:#555;`;
        const verifyHistoryFilesChk = document.createElement('input');
        verifyHistoryFilesChk.type = 'checkbox';
        verifyHistoryFilesChk.id = 'pixiv-dl-verify-history-files';
        verifyHistoryFilesChk.checked = verifyHistoryFilesEnabled;
        const verifyHistoryFilesLabel = document.createElement('label');
        verifyHistoryFilesLabel.htmlFor = 'pixiv-dl-verify-history-files';
        verifyHistoryFilesLabel.textContent = '实际目录检测';
        verifyHistoryFilesLabel.style.cursor = 'pointer';
        const verifyHistoryFilesHelp = document.createElement('span');
        verifyHistoryFilesHelp.textContent = '?';
        verifyHistoryFilesHelp.title = VERIFY_HISTORY_FILES_TOOLTIP;
        verifyHistoryFilesHelp.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;width:14px;height:14px;border:1px solid #999;border-radius:50%;color:#666;font-size:10px;font-weight:700;line-height:1;cursor:help;user-select:none;flex-shrink:0;';
        verifyHistoryFilesRow.appendChild(verifyHistoryFilesChk);
        verifyHistoryFilesRow.appendChild(verifyHistoryFilesLabel);
        verifyHistoryFilesRow.appendChild(verifyHistoryFilesHelp);

        skipHistoryChk.addEventListener('change', () => {
            GM_setValue(KEY_SKIP_HISTORY, skipHistoryChk.checked);
            verifyHistoryFilesRow.style.display = skipHistoryChk.checked ? 'flex' : 'none';
        });
        verifyHistoryFilesChk.addEventListener('change', () => {
            GM_setValue(KEY_VERIFY_HISTORY_FILES, verifyHistoryFilesChk.checked);
        });

        const artworkIdDiv = document.createElement('div');
        artworkIdDiv.innerHTML = `作品ID: ${artworkId}`;
        artworkIdDiv.style.cssText = `
            font-size: 12px;
            color: #666;
            text-align: center;
            margin-bottom: 5px;
        `;

        const backendStatusDiv = document.createElement('div');
        backendStatusDiv.id = 'backend-status';
        backendStatusDiv.innerHTML = '检查后端状态...';
        backendStatusDiv.style.cssText = `
            font-size: 11px;
            color: #888;
            text-align: center;
            margin-bottom: 5px;
        `;

        const infoDiv = document.createElement('div');
        infoDiv.innerHTML = '使用Java后端服务下载，支持完整文件夹结构';
        infoDiv.style.cssText = `
            font-size: 10px;
            color: #999;
            text-align: center;
        `;

        // 配额栏
        const quotaBarDiv = document.createElement('div');
        quotaBarDiv.id = 'pixiv-single-quota-bar';
        quotaBarDiv.style.cssText = 'display:none;margin-bottom:6px;padding:5px 6px;background:#f8f9fa;border-radius:4px;font-size:11px;color:#555;';

        // 压缩包下载卡片
        const archiveCardDiv = document.createElement('div');
        archiveCardDiv.id = 'pixiv-single-archive-card';
        archiveCardDiv.style.cssText = 'display:none;margin-bottom:8px;padding:8px;background:#fff8e1;border:2px solid #ffc107;border-radius:4px;font-size:11px;';

        container.appendChild(titleDiv);
        container.appendChild(statusDiv);
        container.appendChild(button);
        container.appendChild(bookmarkRow);
        container.appendChild(skipHistoryRow);
        container.appendChild(verifyHistoryFilesRow);
        container.appendChild(artworkIdDiv);
        container.appendChild(backendStatusDiv);
        container.appendChild(infoDiv);
        container.appendChild(quotaBarDiv);
        container.appendChild(archiveCardDiv);
        document.body.appendChild(container);

        // 渲染配额栏
        if (quotaInfo.enabled) renderSingleQuotaBar();

        // 恢复已有压缩包卡片（如果有）
        if (window._pixivSingleArchiveState) {
            const s = window._pixivSingleArchiveState;
            renderSingleArchiveCard(s.token, s.expireSec, s.ready);
        }

        // 检查后端状态
        checkBackendStatus().then(available => {
            const statusDiv = document.getElementById('backend-status');
            if (statusDiv) {
                statusDiv.innerHTML = available ?
                    '✅ 后端服务可用' :
                    '❌ 后端服务未启动';
                statusDiv.style.color = available ? '#28a745' : '#dc3545';
            }
        });
    }

    // 渲染配额栏
    function renderSingleQuotaBar() {
        const bar = document.getElementById('pixiv-single-quota-bar');
        if (!bar || !quotaInfo.enabled) return;
        const pct = Math.min(100, Math.round(quotaInfo.artworksUsed / quotaInfo.maxArtworks * 100));
        const color = pct >= 90 ? '#dc3545' : pct >= 70 ? '#ffc107' : '#28a745';
        bar.style.display = 'block';
        bar.innerHTML = `<div style="display:flex;align-items:center;gap:5px;">
          <span style="white-space:nowrap;">配额：${quotaInfo.artworksUsed}/${quotaInfo.maxArtworks}</span>
          <div style="flex:1;height:4px;background:#e0e0e0;border-radius:2px;overflow:hidden;">
            <div style="height:100%;width:${pct}%;background:${color};border-radius:2px;"></div>
          </div>
          <span style="color:#888;">${pct}%</span>
        </div>`;
    }

    function renderSingleArchiveCard(token, expireSec, ready) {
        window._pixivSingleArchiveState = { token, expireSec, ready };
        const card = document.getElementById('pixiv-single-archive-card');
        if (!card) return;
        card.style.display = 'block';
        card.innerHTML = `<div style="font-weight:bold;color:#856404;margin-bottom:4px;">已达到下载限额</div>
          <div id="pixiv-single-ac-status" style="color:#666;">${ready ? '压缩包已就绪：' : '正在打包已下载文件，请稍候...'}</div>
          <div id="pixiv-single-ac-dl" style="display:${ready ? 'block' : 'none'};margin-top:4px;"></div>
          <div id="pixiv-single-ac-expired" style="display:none;color:#dc3545;font-weight:bold;">下载链接已过期</div>`;
        if (ready) {
            activateSingleArchiveDl(token, expireSec);
        } else {
            pollSingleArchive(token, expireSec);
        }
    }

    function pollSingleArchive(token, expireSec) {
        const timer = setInterval(() => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `${getArchiveStatusBase()}/${token}`,
                onload: (res) => {
                    try {
                        const data = JSON.parse(res.responseText);
                        if (data.status === 'ready') {
                            clearInterval(timer);
                            activateSingleArchiveDl(token, data.expireSeconds || expireSec);
                        } else if (data.status === 'expired') {
                            clearInterval(timer);
                            const expired = document.getElementById('pixiv-single-ac-expired');
                            const status = document.getElementById('pixiv-single-ac-status');
                            if (expired) expired.style.display = 'block';
                            if (status) status.textContent = '';
                        } else if (data.status === 'empty') {
                            clearInterval(timer);
                            const status = document.getElementById('pixiv-single-ac-status');
                            if (status) status.textContent = '暂无可打包文件';
                        }
                    } catch {}
                },
                onerror: () => {}
            });
        }, 2000);
    }

    function activateSingleArchiveDl(token, expireSec) {
        const statusEl = document.getElementById('pixiv-single-ac-status');
        const dlEl = document.getElementById('pixiv-single-ac-dl');
        if (statusEl) statusEl.textContent = '压缩包已就绪：';
        if (dlEl) {
            dlEl.style.display = 'block';
            const filename = 'pixiv_download_' + token.substring(0, 8) + '.zip';
            dlEl.innerHTML = `<a href="${getArchiveDownloadBase()}/${token}" download="${filename}"
              style="display:inline-block;padding:4px 10px;background:#28a745;color:white;
                     border-radius:4px;text-decoration:none;font-size:11px;font-weight:bold;">
              下载压缩包
            </a>
            <span id="pixiv-single-ac-countdown" style="font-size:10px;color:#888;margin-left:6px;"></span>`;
            let remaining = Math.max(0, parseInt(expireSec));
            const fmtSec = (s) => {
                s = Math.max(0, Math.round(s));
                const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
                if (h > 0) return h + 'h ' + String(m).padStart(2,'0') + 'm ' + String(sec).padStart(2,'0') + 's';
                return String(m).padStart(2,'0') + ':' + String(sec).padStart(2,'0');
            };
            const el = () => document.getElementById('pixiv-single-ac-countdown');
            if (el()) el().textContent = '有效期：' + fmtSec(remaining);
            const timer = setInterval(() => {
                remaining--;
                if (remaining <= 0) {
                    clearInterval(timer);
                    const expired = document.getElementById('pixiv-single-ac-expired');
                    if (dlEl) dlEl.style.display = 'none';
                    if (expired) expired.style.display = 'block';
                } else {
                    if (el()) el().textContent = '有效期：' + fmtSec(remaining);
                }
            }, 1000);
        }
    }

    function showQuotaExceededUI(data) {
        window._pixivSingleArchiveState = null;
        const card = document.getElementById('pixiv-single-archive-card');
        if (card) {
            renderSingleArchiveCard(data.archiveToken, data.archiveExpireSeconds || 3600, false);
        } else {
            // UI 还未创建，暂存状态，等下次 createDownloadUI 时恢复
            window._pixivSingleArchiveState = { token: data.archiveToken, expireSec: data.archiveExpireSeconds || 3600, ready: false };
            updateDownloadUI();
        }
    }

    // 更新下载UI状态
    function updateDownloadUI() {
        createDownloadUI();
    }

    // 初始化下载UI
    async function initDownloadUI() {
        try {
            // 等待页面基本加载
            await waitForPageLoad();

            // 等待Pixiv主要内容区域加载
            setTimeout(createDownloadUI, 1000);

            // 监听URL变化
            let lastUrl = location.href;
            new MutationObserver(() => {
                const url = location.href;
                if (url !== lastUrl) {
                    lastUrl = url;
                    setTimeout(createDownloadUI, 500);
                }
            }).observe(document, {subtree: true, childList: true});

        } catch (error) {
            console.log('初始化下载UI失败:', error);
            setTimeout(createDownloadUI, 3000);
        }
    }

    // 注册菜单命令
    GM_registerMenuCommand('通过后端下载当前作品', downloadImages);
    GM_registerMenuCommand('⚙️ 设置服务器地址', () => {
        const input = prompt('请输入后端服务器地址（不含末尾斜杠）:', serverBase);
        if (input !== null) {
            serverBase = input.trim().replace(/\/$/, '') || 'http://localhost:6999';
            GM_setValue(KEY_SERVER_URL, serverBase);
            alert('服务器地址已更新为: ' + serverBase);
        }
    });

    // 添加快捷键支持 (Ctrl+Shift+J)
    document.addEventListener('keydown', function (e) {
        if (e.ctrlKey && e.shiftKey && e.key === 'J') {
            e.preventDefault();
            downloadImages();
        }
    });

    // 首次启动提示
    checkExternalServerNotice();

    // 第一步：检查登录状态，通过后再初始化
    (async () => {
        const authed = await checkLoginStatus();
        if (!authed) return;

        // 初始化配额信息
        initQuota().then(data => {
            if (data && data.enabled) {
                quotaInfo = {
                    enabled: true,
                    artworksUsed: data.artworksUsed || 0,
                    maxArtworks: data.maxArtworks || 50,
                    resetSeconds: data.resetSeconds || 0
                };
                // 恢复已有压缩包
                if (data.archive && data.archive.token) {
                    window._pixivSingleArchiveState = {
                        token: data.archive.token,
                        expireSec: data.archive.expireSeconds || 3600,
                        ready: data.archive.status === 'ready'
                    };
                }
                renderSingleQuotaBar();
            }
        });

        // 启动初始化
        initDownloadUI();
    })();
})();
