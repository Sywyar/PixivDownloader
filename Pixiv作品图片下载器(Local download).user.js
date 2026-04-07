// ==UserScript==
// @name         Pixiv作品图片下载器
// @namespace    http://tampermonkey.net/
// @version      1.2
// @description  下载Pixiv作品的所有图片，并记录已下载的作品
// @author       Rewritten by ChatGPT,Claude,Sywyar
// @match        https://www.pixiv.net/*
// @grant        GM_registerMenuCommand
// @grant        GM_xmlhttpRequest
// @grant        GM_download
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_listValues
// @grant        GM_deleteValue
// @connect      i.pximg.net
// @connect      www.pixiv.net
// @run-at       document-start
// ==/UserScript==

(function() {
    'use strict';

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

    // 等待特定元素出现
    function waitForElement(selector, timeout = 10000) {
        return new Promise((resolve, reject) => {
            if (document.querySelector(selector)) {
                resolve(document.querySelector(selector));
                return;
            }

            const observer = new MutationObserver(() => {
                if (document.querySelector(selector)) {
                    observer.disconnect();
                    resolve(document.querySelector(selector));
                }
            });

            observer.observe(document.body, {
                childList: true,
                subtree: true
            });

            setTimeout(() => {
                observer.disconnect();
                reject(new Error(`Element ${selector} not found within ${timeout}ms`));
            }, timeout);
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
                onload: function(response) {
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
                onerror: function(error) {
                    reject(error);
                }
            });
        });
    }

    // 检测作品类型
    async function getArtworkType(artworkId) {
        return new Promise((resolve) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `https://www.pixiv.net/ajax/illust/${artworkId}`,
                headers: { 'Referer': 'https://www.pixiv.net/' },
                onload: function(response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        resolve(data.error ? 0 : (data.body.illustType || 0));
                    } catch (e) { resolve(0); }
                },
                onerror: function() { resolve(0); }
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

        // 检查是否已下载
        if (isAlreadyDownloaded(artworkId)) {
            const downloadedInfo = GM_getValue('downloadedArtworks', {})[artworkId];
            const confirmDownload = confirm(
                `作品 ${artworkId} 已经在 ${new Date(downloadedInfo.timestamp).toLocaleString()} 下载过\n` +
                `保存位置: 文件夹 ${downloadedInfo.folder}\n` +
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

            // 检测动图
            const illustType = await getArtworkType(artworkId);
            if (illustType === 2) {
                alert(`作品 ${artworkId} 是动图（ugoira）。\n此版本不支持动图合成，请使用「Pixiv作品图片下载器（Java后端版）」下载动图，后端会自动合成为WebP格式。`);
                return;
            }

            const imageUrls = await getImageUrls(artworkId);

            if (imageUrls.length === 0) {
                alert('未找到图片');
                return;
            }

            // 获取下一个文件夹索引
            let folderIndex = GM_getValue('downloadFolderIndex', 0);

            // 创建下载文件夹
            const folderName = folderIndex.toString();

            // 下载所有图片
            let successCount = 0;
            for (let i = 0; i < imageUrls.length; i++) {
                const imageUrl = imageUrls[i];
                const extension = imageUrl.split('.').pop();
                // 修改这里：移除文件夹路径，直接使用文件名
                const filename = `${artworkId}_p${i}.${extension}`;

                try {
                    await new Promise((resolve, reject) => {
                        GM_download({
                            url: imageUrl,
                            name: filename,
                            headers: {
                                'Referer': 'https://www.pixiv.net/'
                            },
                            onload: function() {
                                console.log(`下载完成: ${filename}`);
                                successCount++;
                                resolve();
                            },
                            onerror: function(error) {
                                console.error(`下载失败: ${filename}`, error);
                                reject(error);
                            }
                        });
                    });
                } catch (error) {
                    console.error(`第 ${i + 1} 张图片下载失败:`, error);
                    // 继续下载其他图片
                }

                // 添加延迟避免请求过快
                await new Promise(resolve => setTimeout(resolve, 1000));
            }

            // 更新文件夹索引
            GM_setValue('downloadFolderIndex', folderIndex + 1);

            // 记录已下载的作品
            if (successCount > 0) {
                // 修改这里：文件夹名称改为空字符串，因为不再使用文件夹
                markAsDownloaded(artworkId, "默认下载目录", successCount);
                updateDownloadUI(); // 更新UI状态
            }

            alert(`下载完成！\n成功下载: ${successCount}/${imageUrls.length} 张图片\n保存到: 默认下载目录`);

        } catch (error) {
            console.error('下载失败:', error);
            alert('下载失败: ' + error.message);
        }
    }

    // 显示下载历史
    function showDownloadHistory() {
        const downloadedList = GM_getValue('downloadedArtworks', {});
        const artworkIds = Object.keys(downloadedList);

        if (artworkIds.length === 0) {
            alert('还没有下载记录');
            return;
        }

        let historyText = `已下载作品数量: ${artworkIds.length}\n\n`;

        artworkIds.sort((a, b) => new Date(downloadedList[b].timestamp) - new Date(downloadedList[a].timestamp));

        artworkIds.forEach(artworkId => {
            const info = downloadedList[artworkId];
            historyText += `作品ID: ${artworkId}\n`;
            historyText += `下载时间: ${new Date(info.timestamp).toLocaleString()}\n`;
            historyText += `保存位置: ${info.folder}\n`;
            historyText += `图片数量: ${info.imageCount}张\n`;
            historyText += `---\n`;
        });

        alert(historyText);
    }

    // 清空下载记录
    function clearDownloadHistory() {
        const confirmClear = confirm('确定要清空所有下载记录吗？此操作不可恢复。');
        if (confirmClear) {
            GM_setValue('downloadedArtworks', {});
            alert('下载记录已清空');
            updateDownloadUI();
        }
    }

    // 重置文件夹索引
    function resetFolderIndex() {
        const confirmReset = confirm('确定要重置文件夹索引吗？下次下载将从文件夹0开始。');
        if (confirmReset) {
            GM_setValue('downloadFolderIndex', 0);
            alert('文件夹索引已重置');
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
            min-width: 200px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
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
        button.innerHTML = '📥 下载所有图片';
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
            margin-top: 5px;
        `;

        container.appendChild(statusDiv);
        container.appendChild(button);
        container.appendChild(artworkIdDiv);
        document.body.appendChild(container);
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
            await waitForElement('main, [role="main"], .sc-1n7super, .gtm-first-view', 15000);

            // 创建UI
            createDownloadUI();

            // 监听URL变化（Pixiv是单页应用）
            let lastUrl = location.href;
            new MutationObserver(() => {
                const url = location.href;
                if (url !== lastUrl) {
                    lastUrl = url;
                    // 延迟一点时间确保新页面内容已加载
                    setTimeout(createDownloadUI, 500);
                }
            }).observe(document, { subtree: true, childList: true });

            // 额外监听：每2秒检查一次页面变化（备用方案）
            setInterval(() => {
                const artworkId = getArtworkId();
                if (artworkId && !document.getElementById('pixiv-downloader-ui')) {
                    createDownloadUI();
                }
            }, 2000);

        } catch (error) {
            console.log('初始化下载UI失败:', error);
            // 如果失败，尝试简单方式添加UI
            setTimeout(createDownloadUI, 3000);
        }
    }

    // 注册菜单命令
    GM_registerMenuCommand('下载当前作品所有图片', downloadImages);
    GM_registerMenuCommand('查看下载历史', showDownloadHistory);
    GM_registerMenuCommand('清空下载记录', clearDownloadHistory);
    GM_registerMenuCommand('重置文件夹索引', resetFolderIndex);

    // 添加快捷键支持 (Ctrl+Shift+D)
    document.addEventListener('keydown', function(e) {
        if (e.ctrlKey && e.shiftKey && e.key === 'D') {
            e.preventDefault();
            downloadImages();
        }
    });

    // 启动初始化
    initDownloadUI();
})();