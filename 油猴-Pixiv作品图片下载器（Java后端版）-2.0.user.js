// ==UserScript==
// @name         Pixiv作品图片下载器（Java后端版）
// @namespace    http://tampermonkey.net/
// @version      2.0
// @description  通过Java后端服务下载Pixiv作品图片
// @author       You
// @match        https://www.pixiv.net/*
// @grant        GM_registerMenuCommand
// @grant        GM_xmlhttpRequest
// @grant        GM_setValue
// @grant        GM_getValue
// @connect      i.pximg.net
// @connect      www.pixiv.net
// @connect      localhost
// @run-at       document-start
// ==/UserScript==

(function () {
    'use strict';

    // 配置后端服务地址
    const BACKEND_URL = "http://localhost:6999/api/download/pixiv";

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

    // 检查是否已下载过
    function isAlreadyDownloaded(artworkId) {
        const downloadedList = GM_getValue('downloadedArtworks', {});
        return downloadedList.hasOwnProperty(artworkId);
    }

    // 记录已下载的作品
    function markAsDownloaded(artworkId, folderName, imageCount) {
        const downloadedList = GM_getValue('downloadedArtworks', {});
        downloadedList[artworkId] = {
            timestamp: new Date().toISOString(),
            folder: folderName,
            imageCount: imageCount,
            url: window.location.href
        };
        GM_setValue('downloadedArtworks', downloadedList);
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

    // 发送下载请求到后端
    async function sendDownloadRequest(artworkId, imageUrls) {
        return new Promise((resolve, reject) => {
            const requestData = {
                artworkId: parseInt(artworkId),
                imageUrls: imageUrls,
                referer: 'https://www.pixiv.net/'
            };

            GM_xmlhttpRequest({
                method: 'POST',
                url: BACKEND_URL,
                headers: {
                    'Content-Type': 'application/json'
                },
                data: JSON.stringify(requestData),
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        if (response.status === 200 && data.success) {
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
                url: 'http://localhost:6999/api/download/status',
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
    async function downloadImages() {
        const artworkId = getArtworkId();
        if (!artworkId) {
            alert('无法获取作品ID');
            return;
        }

        // 检查后端服务是否可用
        const isBackendAvailable = await checkBackendStatus();
        if (!isBackendAvailable) {
            alert('后端下载服务未启动！\n请确保Java Spring程序正在localhost:6999运行');
            return;
        }

        // 检查是否已下载
        if (isAlreadyDownloaded(artworkId)) {
            const downloadedInfo = GM_getValue('downloadedArtworks', {})[artworkId];
            const confirmDownload = confirm(
                `作品 ${artworkId} 已经在 ${new Date(downloadedInfo.timestamp).toLocaleString()} 下载过\n` +
                `文件夹: ${downloadedInfo.folder}\n` +
                `图片数量: ${downloadedInfo.imageCount}张\n\n` +
                `是否重新下载？`
            );

            if (!confirmDownload) {
                return;
            }
        }

        try {
            // 显示下载开始提示
            alert(`开始下载作品 ${artworkId} 的图片...`);

            const imageUrls = await getImageUrls(artworkId);

            if (imageUrls.length === 0) {
                alert('未找到图片');
                return;
            }

            // 发送下载请求到后端
            const response = await sendDownloadRequest(artworkId, imageUrls);

            // 记录下载信息（这里我们不知道具体的文件夹编号，后端会记录）
            markAsDownloaded(artworkId, '后端处理中', imageUrls.length);
            updateDownloadUI();

            alert(`下载任务已提交到后端处理！\n图片数量: ${imageUrls.length}张\n${response.message}`);

        } catch (error) {
            console.error('下载失败:', error);
            alert('下载失败: ' + error.message);
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

        const isDownloaded = isAlreadyDownloaded(artworkId);

        const container = document.createElement('div');
        container.id = 'pixiv-downloader-ui';
        container.style.cssText = `
            position: fixed;
            top: 100px;
            right: 20px;
            z-index: 10000;
            background: white;
            border: 2px solid ${isDownloaded ? '#ff9500' : '#0096fa'};
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
        statusDiv.innerHTML = isDownloaded ?
            '✅ 已下载过此作品' :
            '⬇️ 可下载此作品';
        statusDiv.style.cssText = `
            font-weight: bold;
            margin-bottom: 8px;
            color: ${isDownloaded ? '#ff9500' : '#0096fa'};
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

        container.appendChild(titleDiv);
        container.appendChild(statusDiv);
        container.appendChild(button);
        container.appendChild(artworkIdDiv);
        container.appendChild(backendStatusDiv);
        container.appendChild(infoDiv);
        document.body.appendChild(container);

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

    // 添加快捷键支持 (Ctrl+Shift+J)
    document.addEventListener('keydown', function (e) {
        if (e.ctrlKey && e.shiftKey && e.key === 'J') {
            e.preventDefault();
            downloadImages();
        }
    });

    // 启动初始化
    initDownloadUI();
})();