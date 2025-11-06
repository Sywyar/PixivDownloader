// ==UserScript==
// @name         Pixiv作品批量下载器（N-Tab版）- 增强版
// @namespace    http://tampermonkey.net/
// @version      2.0
// @description  在Pixiv主页批量下载N-Tab中收藏的作品，支持暂停、间隔控制和SSE实时进度显示
// @author       You
// @match        https://www.pixiv.net/
// @grant        GM_xmlhttpRequest
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_registerMenuCommand
// @grant        GM_notification
// @connect      i.pximg.net
// @connect      www.pixiv.net
// @connect      localhost
// @run-at       document-end
// ==/UserScript==

(function () {
    'use strict';

    // 配置后端服务地址
    const BACKEND_URL = "http://localhost:6999/api/download/pixiv";

    // 检查是否在Pixiv主页
    function isPixivHomepage() {
        return window.location.href === 'https://www.pixiv.net/' ||
            window.location.href === 'https://www.pixiv.net';
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

    // SSE连接管理器
    const sseConnections = new Map();

    // 创建SSE连接
    function createSSEConnection(artworkId) {
        // 如果已有连接，先关闭
        if (sseConnections.has(artworkId)) {
            closeSSEConnection(artworkId);
        }

        const eventSource = new EventSource(`http://localhost:6999/api/sse/download/${artworkId}`);

        eventSource.addEventListener('download-status', function (event) {
            try {
                const status = JSON.parse(event.data);
                updateArtworkStatus(artworkId, status);

                // 如果下载完成或失败，关闭SSE连接
                if (status.completed || status.failed || status.cancelled) {
                    closeSSEConnection(artworkId);
                }
            } catch (error) {
                console.error('SSE消息解析失败:', error);
            }
        });

        eventSource.addEventListener('heartbeat', function (event) {
            // 心跳消息，保持连接活跃
            console.log(`SSE心跳: ${artworkId}`);
        });

        eventSource.onerror = function (error) {
            console.error(`SSE连接错误 (${artworkId}):`, error);
            closeSSEConnection(artworkId);
        };

        sseConnections.set(artworkId, eventSource);
        console.log(`SSE连接已建立: ${artworkId}`);
    }

    // 关闭SSE连接
    function closeSSEConnection(artworkId) {
        const eventSource = sseConnections.get(artworkId);
        if (eventSource) {
            eventSource.close();
            sseConnections.delete(artworkId);
            console.log(`SSE连接已关闭: ${artworkId}`);
            
            // 通知后端安全关闭连接
            GM_xmlhttpRequest({
                method: 'POST',
                url: `http://localhost:6999/api/sse/close/${artworkId}`,
                onload: function(response) {
                    console.log(`后端SSE连接安全关闭: ${artworkId}`);
                },
                onerror: function(error) {
                    console.warn(`通知后端关闭SSE连接失败: ${artworkId}`, error);
                }
            });
        }
    }

    // 获取下载状态（兼容性函数，主要用于初始状态检查）
    async function getDownloadStatus(artworkId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `http://localhost:6999/api/download/status/${artworkId}`,
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        resolve(data);
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

    // 取消下载
    async function cancelDownload(artworkId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'POST',
                url: `http://localhost:6999/api/download/cancel/${artworkId}`,
                onload: function (response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        resolve(data);
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

    // 创建批量下载界面
    function createBatchDownloadUI() {
        if (!isPixivHomepage()) return;

        // 移除已存在的UI
        const existingUI = document.getElementById('pixiv-batch-downloader-ui');
        if (existingUI) {
            existingUI.remove();
        }

        const container = document.createElement('div');
        container.id = 'pixiv-batch-downloader-ui';
        container.style.cssText = `
            position: fixed;
            top: 120px;
            right: 20px;
            z-index: 10000;
            background: white;
            border: 2px solid #28a745;
            border-radius: 8px;
            padding: 15px;
            box-shadow: 0 4px 20px rgba(0,0,0,0.3);
            min-width: 400px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            max-height: 80vh;
            overflow-y: auto;
        `;

        const titleDiv = document.createElement('div');
        titleDiv.innerHTML = '🎨 N-Tab批量下载器 v2.0';
        titleDiv.style.cssText = `
            font-weight: bold;
            margin-bottom: 15px;
            color: #333;
            text-align: center;
            font-size: 16px;
            border-bottom: 2px solid #eee;
            padding-bottom: 10px;
        `;

        const statusDiv = document.createElement('div');
        statusDiv.id = 'batch-status';
        statusDiv.innerHTML = '准备就绪';
        statusDiv.style.cssText = `
            margin-bottom: 10px;
            color: #666;
            font-size: 12px;
            text-align: center;
        `;

        const statsDiv = document.createElement('div');
        statsDiv.id = 'batch-stats';
        statsDiv.innerHTML = '队列: 0 | 成功: 0 | 失败: 0 | 进行中: 0';
        statsDiv.style.cssText = `
            margin-bottom: 10px;
            color: #007bff;
            font-size: 12px;
            text-align: center;
            font-weight: bold;
        `;

        const inputSection = document.createElement('div');
        inputSection.style.cssText = 'margin-bottom: 15px;';

        const textarea = document.createElement('textarea');
        textarea.id = 'ntab-data-input';
        textarea.placeholder = `粘贴N-Tab导出的数据，格式如：
https://www.pixiv.net/artworks/136498348 | #血の女王 Marry and the dog - こめり的插画 - pixiv
https://www.pixiv.net/artworks/136536736 | #第五人格 お誘い - こめり的插画 - pixiv`;
        textarea.style.cssText = `
            width: 100%;
            height: 120px;
            margin-bottom: 10px;
            padding: 8px;
            border: 1px solid #ddd;
            border-radius: 4px;
            font-size: 12px;
            resize: vertical;
        `;

        // 设置区域
        const settingsDiv = document.createElement('div');
        settingsDiv.style.cssText = 'margin-bottom: 15px;';
        settingsDiv.innerHTML = `
    <div style="display: flex; align-items: center; margin-bottom: 10px;">
        <label style="font-size: 12px; margin-right: 10px; width: 120px;">下载间隔(秒):</label>
        <input type="number" id="download-interval" value="2" min="1" max="60" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
    </div>
    <div style="display: flex; align-items: center; margin-bottom: 10px;">
        <label style="font-size: 12px; margin-right: 10px; width: 120px;">实时通知:</label>
        <span style="font-size: 12px; color: #28a745;">SSE已启用</span>
    </div>
    <div style="display: flex; align-items: center; margin-bottom: 10px;">
        <label style="font-size: 12px; margin-right: 10px; width: 120px;">最大并发数:</label>
        <input type="number" id="max-concurrent" value="1" min="1" max="5" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
    </div>
`;

        const buttonContainer = document.createElement('div');
        buttonContainer.style.cssText = `
            display: flex;
            flex-direction: column;
            gap: 8px;
            margin-bottom: 10px;
        `;

        const parseBtn = document.createElement('button');
        parseBtn.innerHTML = '📋 解析N-Tab数据';
        parseBtn.style.cssText = `
            width: 100%;
            background: #007bff;
            color: white;
            border: none;
            padding: 10px;
            border-radius: 5px;
            cursor: pointer;
            font-size: 14px;
        `;

        const startBtn = document.createElement('button');
        startBtn.innerHTML = '🚀 开始批量下载';
        startBtn.id = 'start-batch-btn';
        startBtn.style.cssText = `
            width: 100%;
            background: #28a745;
            color: white;
            border: none;
            padding: 10px;
            border-radius: 5px;
            cursor: pointer;
            font-size: 14px;
            font-weight: bold;
        `;
        startBtn.disabled = true;

        const pauseBtn = document.createElement('button');
        pauseBtn.innerHTML = '⏸️ 暂停下载';
        pauseBtn.id = 'pause-batch-btn';
        pauseBtn.style.cssText = `
            width: 100%;
            background: #ffc107;
            color: black;
            border: none;
            padding: 10px;
            border-radius: 5px;
            cursor: pointer;
            font-size: 14px;
        `;
        pauseBtn.disabled = true;

        const clearQueueBtn = document.createElement('button');
        clearQueueBtn.innerHTML = '🗑️ 清除队列';
        clearQueueBtn.id = 'clear-queue-btn';
        clearQueueBtn.style.cssText = `
            width: 100%;
            background: #6c757d;
            color: white;
            border: none;
            padding: 10px;
            border-radius: 5px;
            cursor: pointer;
            font-size: 14px;
        `;
        clearQueueBtn.disabled = false;

        const currentDownloadDiv = document.createElement('div');
        currentDownloadDiv.id = 'current-download';
        currentDownloadDiv.style.cssText = `
            margin-bottom: 10px;
            padding: 8px;
            background: #f8f9fa;
            border-radius: 5px;
            border-left: 4px solid #007bff;
            font-size: 11px;
        `;

        const queueContainer = document.createElement('div');
        queueContainer.id = 'queue-container';
        queueContainer.style.cssText = `
            max-height: 250px;
            overflow-y: auto;
            border: 1px solid #ddd;
            border-radius: 5px;
            padding: 10px;
            margin-bottom: 10px;
            background: #f8f9fa;
            font-size: 11px;
        `;

        const backendStatusDiv = document.createElement('div');
        backendStatusDiv.id = 'batch-backend-status';
        backendStatusDiv.innerHTML = '检查后端状态...';
        backendStatusDiv.style.cssText = `
            font-size: 11px;
            color: #888;
            text-align: center;
            margin-bottom: 5px;
        `;

        inputSection.appendChild(textarea);
        buttonContainer.appendChild(parseBtn);
        buttonContainer.appendChild(startBtn);
        buttonContainer.appendChild(pauseBtn);
        buttonContainer.appendChild(clearQueueBtn);

        container.appendChild(titleDiv);
        container.appendChild(statusDiv);
        container.appendChild(statsDiv);
        container.appendChild(inputSection);
        container.appendChild(settingsDiv);
        container.appendChild(buttonContainer);
        container.appendChild(currentDownloadDiv);
        container.appendChild(queueContainer);
        container.appendChild(backendStatusDiv);

        document.body.appendChild(container);

        // 事件监听
        parseBtn.addEventListener('click', parseInputData);
        startBtn.addEventListener('click', startBatchDownload);
        pauseBtn.addEventListener('click', pauseBatchDownload);
        clearQueueBtn.addEventListener('click', clearQueue);

        // 检查后端状态
        function updateBackendStatusDisplay() {
            checkBackendStatus().then(available => {
                const statusDiv = document.getElementById('batch-backend-status');
                if (statusDiv) {
                    statusDiv.innerHTML = available ?
                        '✅ 后端服务可用' :
                        '❌ 后端服务未启动';
                    statusDiv.style.color = available ? '#28a745' : '#dc3545';
                }
            });
        }

        // 初始更新
        updateBackendStatusDisplay();

        // 添加交互时更新服务器状态的事件监听
        container.addEventListener('click', updateBackendStatusDisplay);
        container.addEventListener('focusin', updateBackendStatusDisplay);
        document.getElementById('ntab-data-input').addEventListener('input', updateBackendStatusDisplay);

        // 加载保存的队列
        loadSavedQueue();
    }

    // 全局状态
    let downloadQueue = [];
    let isBatchRunning = false;
    let isBatchPaused = false;
    let currentDownloadIndex = -1;
    let completedCount = 0;
    let successCount = 0;
    let failCount = 0;
    let activeDownloads = 0;
    let currentArtworkId = null;

    // 解析输入数据
    function parseInputData() {
        const input = document.getElementById('ntab-data-input').value.trim();
        if (!input) {
            updateStatus('请输入N-Tab导出的数据', 'error');
            return;
        }

        // 重置所有状态，确保每次重新解析都是全新的开始
        isBatchRunning = false;
        isBatchPaused = false;
        currentDownloadIndex = -1;
        completedCount = 0;
        successCount = 0;
        failCount = 0;
        activeDownloads = 0;
        currentArtworkId = null;

        const artworks = [];
        const lines = input.split('\n').filter(line => line.trim());

        let validCount = 0;
        lines.forEach((line, index) => {
            const urlMatch = line.match(/https:\/\/www\.pixiv\.net\/artworks\/(\d+)/);
            if (urlMatch) {
                const artworkId = urlMatch[1];
                let title = line.replace(/https:\/\/www\.pixiv\.net\/artworks\/\d+\s*\|\s*/, '').trim();
                if (!title) {
                    title = `作品 ${artworkId}`;
                }

                artworks.push({
                    id: artworkId,
                    title: title,
                    url: `https://www.pixiv.net/artworks/${artworkId}`,
                    status: 'paused',
                    progress: 0,
                    currentImage: 0,
                    totalImages: 0,
                    startTime: null,
                    endTime: null
                });
                validCount++;
            }
        });

        if (artworks.length === 0) {
            updateStatus('未找到有效的Pixiv作品链接', 'error');
            return;
        }

        downloadQueue = artworks;
        updateQueueDisplay();
        updateStatus(`解析成功: 找到 ${validCount} 个作品`, 'success');
        
        // 更新按钮状态
        document.getElementById('start-batch-btn').disabled = false;
        document.getElementById('pause-batch-btn').disabled = true;
        document.getElementById('clear-queue-btn').disabled = false;
        
        updateStats();

        // 保存队列
        saveQueueState();
        
        console.log('N-Tab数据解析完成，队列已重置:', {
            total: downloadQueue.length,
            status: '所有作品初始化为暂停中状态',
            timestamp: new Date().toISOString()
        });
    }

    // 更新队列显示
    function updateQueueDisplay() {
        const container = document.getElementById('queue-container');
        container.innerHTML = '<div style="font-weight: bold; margin-bottom: 5px;">下载队列:</div>';

        if (downloadQueue.length === 0) {
            container.innerHTML += '<div style="color: #666; text-align: center;">队列为空</div>';
            return;
        }

        downloadQueue.forEach((artwork, index) => {
            const item = document.createElement('div');
            item.style.cssText = `
                padding: 5px;
                margin-bottom: 3px;
                background: white;
                border-left: 3px solid ${
                artwork.status === 'completed' ? '#28a745' :
                    artwork.status === 'downloading' ? '#007bff' :
                        artwork.status === 'failed' ? '#dc3545' : '#6c757d'
            };
                font-size: 10px;
            `;

            // 进度显示
            let progressHtml = '';
            if (artwork.status === 'downloading' && artwork.totalImages > 0) {
                // 重要修复：统一使用实际下载数量来计算进度
                const downloadedCount = artwork.downloadedCount || 0;
                const progressPercent = Math.min(Math.round((downloadedCount / artwork.totalImages) * 100), 100);

                progressHtml = `
        <div style="margin-top: 3px;">
            <div style="display: flex; justify-content: space-between; font-size: 9px; margin-bottom: 2px;">
                <span>已下载: ${downloadedCount}/${artwork.totalImages}</span>
                <span>${progressPercent}%</span>
            </div>
            <div style="width: 100%; height: 4px; background: #e0e0e0; border-radius: 2px; overflow: hidden;">
                <div style="height: 100%; background: #007bff; width: ${progressPercent}%; transition: width 0.3s ease;"></div>
            </div>
        </div>
    `;
            }

            item.innerHTML = `
                <div><strong>${artwork.title}</strong></div>
                <div>ID: ${artwork.id} | 状态: ${getStatusText(artwork.status)}</div>
                ${progressHtml}
            `;

            container.appendChild(item);
        });
    }

    // 更新当前下载显示
    function updateCurrentDownloadDisplay() {
        const container = document.getElementById('current-download');

        if (!currentArtworkId) {
            container.innerHTML = '<strong>当前下载:</strong> 无';
            return;
        }

        const artwork = downloadQueue.find(a => a.id === currentArtworkId.toString());
        if (!artwork) {
            container.innerHTML = '<strong>当前下载:</strong> 无';
            return;
        }

        let progressText = '';
        if (artwork.totalImages > 0) {
            // 统一使用已下载图片数量作为进度
            const downloadedCount = artwork.downloadedCount || 0;
            const progressPercent = Math.min(Math.round((downloadedCount / artwork.totalImages) * 100), 100);
            progressText = `
                <div style="margin-top: 5px;">
                    <div style="display: flex; justify-content: space-between; font-size: 10px; margin-bottom: 3px;">
                        <span>已下载 ${downloadedCount} 张 / 共 ${artwork.totalImages} 张</span>
                        <span>${progressPercent}%</span>
                    </div>
                    <div style="width: 100%; height: 6px; background: #e0e0e0; border-radius: 3px; overflow: hidden;">
                        <div style="height: 100%; background: #28a745; width: ${progressPercent}%; transition: width 0.3s ease;"></div>
                    </div>
                </div>
            `;
        }

        container.innerHTML = `
            <strong>当前下载:</strong> ${artwork.title} (ID: ${artwork.id})
            ${progressText}
        `;
    }

    // 获取状态文本
    function getStatusText(status) {
        const statusMap = {
            'pending': '等待中',
            'downloading': '下载中',
            'completed': '已完成',
            'failed': '失败',
            'paused': '暂停中'
        };
        return statusMap[status] || status;
    }

    // 开始批量下载
    async function startBatchDownload() {
        if (downloadQueue.length === 0) {
            updateStatus('下载队列为空', 'error');
            return;
        }

        // 检查后端服务
        const isBackendAvailable = await checkBackendStatus();
        if (!isBackendAvailable) {
            alert('后端下载服务未启动！\n请确保Java Spring程序正在localhost:6999运行');
            return;
        }

        isBatchRunning = true;
        isBatchPaused = false;
        completedCount = 0;
        successCount = 0;
        failCount = 0;
        activeDownloads = 0;

        // 将所有暂停中状态改为等待中
        downloadQueue.forEach(artwork => {
            if (artwork.status === 'paused') {
                artwork.status = 'pending';
            }
        });

        document.getElementById('start-batch-btn').disabled = true;
        document.getElementById('pause-batch-btn').disabled = false;
        document.getElementById('clear-queue-btn').disabled = true;

        updateStatus('开始批量下载...', 'info');
        updateStats();

        // 从上次停止的位置开始，或者从头开始
        if (currentDownloadIndex === -1) {
            currentDownloadIndex = 0;
        }

        // 获取最大并发数
        const maxConcurrent = parseInt(document.getElementById('max-concurrent').value) || 1;

        // 更新队列显示
        updateQueueDisplay();

        // 启动并发下载
        await processDownloadQueueConcurrent(maxConcurrent);
    }

    // 并发处理下载队列
    async function processDownloadQueueConcurrent(maxConcurrent) {
        const promises = [];

        for (let i = 0; i < maxConcurrent; i++) {
            promises.push(processDownloadWorker());
        }

        await Promise.all(promises);

        if (isBatchRunning && !isBatchPaused) {
            updateStatus(`批量下载完成! 成功: ${successCount}, 失败: ${failCount}`, 'success');
            document.getElementById('start-batch-btn').disabled = false;
            document.getElementById('pause-batch-btn').disabled = true;
            document.getElementById('clear-queue-btn').disabled = false;

            // 重置状态
            currentDownloadIndex = -1;
            currentArtworkId = null;
            updateCurrentDownloadDisplay();

            // 清除保存的队列
            GM_setValue('batchDownloadQueue', null);
            GM_setValue('batchDownloadState', null);
        }
    }

    // 下载工作线程
    async function processDownloadWorker() {
        while (isBatchRunning) {
            if (isBatchPaused) {
                // 如果暂停，等待恢复
                await new Promise(resolve => setTimeout(resolve, 500));
                continue;
            }

            // 检查是否还有需要下载的作品
            const nextPendingIndex = downloadQueue.findIndex((artwork, index) => 
                index >= currentDownloadIndex && artwork.status === 'pending'
            );
            
            if (nextPendingIndex === -1) {
                // 没有更多等待中的作品，等待一段时间再检查
                await new Promise(resolve => setTimeout(resolve, 1000));
                continue;
            }

            // 更新当前下载索引并获取作品
            currentDownloadIndex = nextPendingIndex + 1;
            const artwork = downloadQueue[nextPendingIndex];

            // 更新活跃下载数
            activeDownloads++;
            updateStats();

            // 设置当前下载的作品
            currentArtworkId = artwork.id;
            updateCurrentDownloadDisplay();

            artwork.status = 'downloading';
            artwork.startTime = new Date();
            updateQueueDisplay();
            updateStatus(`正在下载: ${artwork.title}`, 'info');

            try {
                await downloadSingleArtwork(artwork);

                // 等待最终状态确认
                const finalStatus = await waitForFinalStatus(artwork.id);
                if (finalStatus && finalStatus.completed) {
                    artwork.status = 'completed';
                    artwork.endTime = new Date();
                    successCount++;
                    updateStatus(`✅ 完成: ${artwork.title} (${finalStatus.downloadedCount}/${finalStatus.totalImages})`, 'success');
                } else {
                    artwork.status = 'failed';
                    artwork.endTime = new Date();
                    failCount++;
                    updateStatus(`❌ 失败: ${artwork.title} - 下载未完成`, 'error');
                }
            } catch (error) {
                artwork.status = 'failed';
                artwork.endTime = new Date();
                failCount++;
                updateStatus(`❌ 失败: ${artwork.title} - ${error.message}`, 'error');
            } finally {
                // 更新活跃下载数
                activeDownloads--;
                updateStats();

                // 如果这是当前下载的作品，清除显示
                if (currentArtworkId === artwork.id) {
                    currentArtworkId = null;
                    updateCurrentDownloadDisplay();
                }
            }

            completedCount++;
            updateQueueDisplay();
            updateStats();

            // 保存队列状态
            saveQueueState();

            // 获取下载间隔（秒转换为毫秒）
            const intervalSeconds = parseInt(document.getElementById('download-interval').value) || 2;
            const intervalMs = intervalSeconds * 1000;

            // 添加延迟避免请求过于频繁
            if (isBatchRunning && !isBatchPaused) {
                updateStatus(`等待 ${intervalSeconds} 秒后下载下一个作品...`, 'info');
                await new Promise(resolve => setTimeout(resolve, intervalMs));
            }
        }
    }

    // 暂停下载
    function pauseBatchDownload() {
        if (!isBatchRunning) return;

        isBatchPaused = true;
        document.getElementById('pause-batch-btn').disabled = true;
        document.getElementById('start-batch-btn').disabled = false;
        
        // 将所有等待中状态改为暂停中
        downloadQueue.forEach(artwork => {
            if (artwork.status === 'pending') {
                artwork.status = 'paused';
            }
        });
        
        // 检查当前是否有正在下载的作品
        const currentArtwork = downloadQueue.find(a => a.id === currentArtworkId);
        if (currentArtwork && currentArtwork.status === 'downloading') {
            updateStatus('下载已暂停，等待当前作品完成...', 'warning');
        } else {
            updateStatus('下载已暂停，队列状态已保存', 'warning');
        }

        // 更新队列显示
        updateQueueDisplay();

        // 保存完整的队列状态，包括所有任务的状态
        saveQueueState();
        
        console.log('队列状态已保存:', {
            total: downloadQueue.length,
            completed: downloadQueue.filter(a => a.status === 'completed').length,
            downloading: downloadQueue.filter(a => a.status === 'downloading').length,
            pending: downloadQueue.filter(a => a.status === 'pending').length,
            paused: downloadQueue.filter(a => a.status === 'paused').length,
            failed: downloadQueue.filter(a => a.status === 'failed').length,
            currentIndex: currentDownloadIndex
        });
    }

    // 清除队列
    function clearQueue() {
        if (downloadQueue.length === 0) {
            updateStatus('队列已为空，无需清除', 'info');
            return;
        }

        // 如果正在下载，先暂停
        if (isBatchRunning) {
            isBatchRunning = false;
            isBatchPaused = false;
            updateStatus('下载已停止，正在清除队列...', 'warning');
        } else {
            updateStatus('正在清除队列...', 'info');
        }

        // 重置所有状态
        downloadQueue = [];
        currentDownloadIndex = -1;
        currentArtworkId = null;
        completedCount = 0;
        successCount = 0;
        failCount = 0;
        activeDownloads = 0;

        // 更新显示
        updateQueueDisplay();
        updateStats();
        updateCurrentDownloadDisplay();

        // 清除保存的队列状态
        GM_setValue('batchDownloadQueue', null);
        
        // 清空输入框
        document.getElementById('ntab-data-input').value = '';
        
        // 更新按钮状态
        document.getElementById('start-batch-btn').disabled = false;
        document.getElementById('pause-batch-btn').disabled = true;
        document.getElementById('clear-queue-btn').disabled = false;
        
        updateStatus('队列已清除', 'success');
        console.log('队列已清除，所有状态已重置');
    }



    // 下载单个作品
    async function downloadSingleArtwork(artwork) {
        try {
            const imageUrls = await getImageUrls(artwork.id);

            if (imageUrls.length === 0) {
                throw new Error('未找到图片');
            }

            // 更新作品的总图片数
            artwork.totalImages = imageUrls.length;
            updateQueueDisplay();
            updateCurrentDownloadDisplay();

            // 创建SSE连接进行实时状态更新
            createSSEConnection(artwork.id);

            // 发送下载请求到后端
            const response = await sendDownloadRequest(artwork.id, imageUrls);

            return response;

        } catch (error) {
            console.error(`下载作品 ${artwork.id} 失败:`, error);
            throw error;
        }
    }

    // 更新作品状态
    function updateArtworkStatus(artworkId, status) {
        const artwork = downloadQueue.find(a => a.id === artworkId.toString());
        if (artwork) {
            // 重要修复：正确处理各种状态字段
            if (status.currentImageIndex !== undefined) {
                // 使用 currentImageIndex + 1 来显示当前正在下载的图片
                artwork.currentImage = status.currentImageIndex + 1;
            }

            // 更新总图片数
            if (status.totalImages !== undefined) {
                artwork.totalImages = status.totalImages;
            }

            // 更新实际下载数量
            if (status.downloadedCount !== undefined) {
                artwork.downloadedCount = status.downloadedCount;
            }

            // 重要修复：统一进度显示，确保currentImage和downloadedCount保持一致
            if (status.currentImageIndex !== undefined) {
                // 统一使用当前图片索引+1作为currentImage，确保与downloadedCount一致
                artwork.currentImage = status.currentImageIndex >= 0 ? status.currentImageIndex + 1 : 0;
            }

            // 重要修复：根据状态更新作品状态
            if (status.completed) {
                artwork.status = 'completed';
                // 确保完成时currentImage和downloadedCount都等于总图片数
                artwork.currentImage = status.totalImages;
                artwork.downloadedCount = status.totalImages;
                updateStatus(`✅ 完成: ${artwork.title} (${status.downloadedCount}/${status.totalImages})`, 'success');
                // 作品完成时立即保存队列状态
                saveQueueState();
            } else if (status.failed) {
                artwork.status = 'failed';
                updateStatus(`❌ 失败: ${artwork.title} - ${status.message}`, 'error');
                // 作品失败时立即保存队列状态
                saveQueueState();
            } else if (status.cancelled) {
                artwork.status = 'failed';
                updateStatus(`⏹️ 取消: ${artwork.title}`, 'warning');
                // 作品取消时立即保存队列状态
                saveQueueState();
            } else if (status.currentImageIndex !== undefined && status.currentImageIndex >= 0) {
                // 下载中状态
                artwork.status = 'downloading';
                const currentImage = status.currentImageIndex + 1;
                const downloadedCount = status.downloadedCount || 0;
                // 确保currentImage和downloadedCount一致
                artwork.currentImage = currentImage;
                artwork.downloadedCount = downloadedCount;
                updateStatus(`📥 下载中: ${artwork.title} (${currentImage}/${status.totalImages}) - 已完成 ${downloadedCount} 张`, 'info');
            } else if (status.currentImageIndex === -1 && status.downloadedCount > 0) {
                // 下载完成但状态未标记为completed的情况
                artwork.status = 'downloading';
                // 确保currentImage和downloadedCount一致
                artwork.currentImage = status.downloadedCount;
                updateStatus(`📥 处理中: ${artwork.title} (${status.downloadedCount}/${status.totalImages})`, 'info');
            }

            // 更新队列显示和当前下载显示
            updateQueueDisplay();
            updateCurrentDownloadDisplay();
        }
    }

    // 等待最终状态确认（基于SSE事件）
    async function waitForFinalStatus(artworkId) {
        return new Promise((resolve) => {
            // 设置超时机制，避免无限等待
            const timeoutId = setTimeout(() => {
                console.warn(`等待作品 ${artworkId} 最终状态超时`);
                resolve({success: false, message: '等待状态超时'});
            }, 300000); // 5分钟超时

            // 监听SSE事件来获取最终状态
            const handleFinalStatus = (event) => {
                const data = JSON.parse(event.data);
                if (data.artworkId === artworkId && (data.completed || data.failed || data.cancelled)) {
                    clearTimeout(timeoutId);
                    resolve(data);
                }
            };

            // 添加事件监听器
            const sseConnection = sseConnections.get(artworkId);
            if (sseConnection) {
                sseConnection.addEventListener('download-status', handleFinalStatus);
            }

            // 清理函数
            return () => {
                clearTimeout(timeoutId);
                if (sseConnection) {
                    sseConnection.removeEventListener('download-status', handleFinalStatus);
                }
            };
        });
    }

    // 保存队列状态
    function saveQueueState() {
        // 创建队列副本，避免修改原始队列
        const queueToSave = JSON.parse(JSON.stringify(downloadQueue));
        
        // 处理下载中的作品状态：保存时保持原状态，不修改为完成状态
        queueToSave.forEach(artwork => {
            if (artwork.status === 'downloading') {
                // 保持下载中状态，不修改为完成状态
                artwork.status = 'downloading';
            }
        });
        
        const queueState = {
            queue: queueToSave,
            currentIndex: currentDownloadIndex, // 保存当前索引，以便恢复时从正确位置继续
            completedCount: completedCount,
            successCount: successCount,
            failCount: failCount,
            activeDownloads: activeDownloads,
            isBatchRunning: isBatchRunning,
            isBatchPaused: isBatchPaused,
            currentArtworkId: currentArtworkId,
            timestamp: new Date().toISOString()
        };

        GM_setValue('batchDownloadQueue', queueState);
    }

    // 加载保存的队列
    function loadSavedQueue() {
        const savedState = GM_getValue('batchDownloadQueue', null);

        if (savedState && savedState.queue && savedState.queue.length > 0) {
            downloadQueue = savedState.queue;
            currentDownloadIndex = savedState.currentIndex || -1;
            completedCount = savedState.completedCount || 0;
            successCount = savedState.successCount || 0;
            failCount = savedState.failCount || 0;
            activeDownloads = savedState.activeDownloads || 0;
            isBatchRunning = savedState.isBatchRunning || false;
            isBatchPaused = savedState.isBatchPaused || false;
            currentArtworkId = savedState.currentArtworkId || null;

            // 确保所有作品状态正确
            downloadQueue.forEach(artwork => {
                // 如果作品状态为下载中，但实际下载数量不足，重新标记为等待中
                if (artwork.status === 'downloading' && 
                    artwork.downloadedCount < artwork.totalImages && 
                    artwork.totalImages > 0) {
                    artwork.status = 'pending';
                }
            });

            updateQueueDisplay();
            updateStats();
            updateCurrentDownloadDisplay();
            
            // 更新按钮状态
            document.getElementById('start-batch-btn').disabled = isBatchRunning;
            document.getElementById('pause-batch-btn').disabled = !isBatchRunning || isBatchPaused;
            document.getElementById('clear-queue-btn').disabled = downloadQueue.length === 0;

            // 详细显示队列状态
            const completedCountVal = downloadQueue.filter(a => a.status === 'completed').length;
            const downloadingCount = downloadQueue.filter(a => a.status === 'downloading').length;
            const pendingCount = downloadQueue.filter(a => a.status === 'pending').length;
            const pausedCount = downloadQueue.filter(a => a.status === 'paused').length;
            const failedCount = downloadQueue.filter(a => a.status === 'failed').length;
            
            let statusMessage = `发现保存的下载队列: `;
            if (completedCountVal > 0) statusMessage += `✅${completedCountVal} `;
            if (downloadingCount > 0) statusMessage += `📥${downloadingCount} `;
            if (pendingCount > 0) statusMessage += `⏳${pendingCount} `;
            if (pausedCount > 0) statusMessage += `⏸️${pausedCount} `;
            if (failedCount > 0) statusMessage += `❌${failedCount} `;
            
            updateStatus(statusMessage, 'info');
            
            console.log('队列加载完成:', {
                total: downloadQueue.length,
                completed: completedCount,
                downloading: downloadingCount,
                pending: pendingCount,
                paused: pausedCount,
                failed: failedCount,
                currentIndex: currentDownloadIndex,
                currentArtworkId: currentArtworkId,
                isBatchRunning: isBatchRunning,
                isBatchPaused: isBatchPaused,
                timestamp: savedState.timestamp
            });
        } else {
            console.log('未发现保存的队列');
        }
    }

    // 更新状态
    function updateStatus(message, type = 'info') {
        const statusDiv = document.getElementById('batch-status');
        statusDiv.textContent = message;
        statusDiv.style.color = {
            'info': '#007bff',
            'success': '#28a745',
            'error': '#dc3545',
            'warning': '#ffc107'
        }[type] || '#666';
    }

    // 更新统计信息
    function updateStats() {
        const statsDiv = document.getElementById('batch-stats');
        const pendingCount = downloadQueue.filter(a => a.status === 'pending' || a.status === 'failed').length;
        statsDiv.textContent = `队列: ${pendingCount} | 成功: ${successCount} | 失败: ${failCount} | 进行中: ${activeDownloads}`;
    }

    // 初始化
    async function init() {
        if (!isPixivHomepage()) return;

        await waitForPageLoad();

        // 等待页面完全加载
        setTimeout(() => {
            createBatchDownloadUI();
        }, 1000);
    }

    // 注册菜单命令
    GM_registerMenuCommand('打开N-Tab批量下载器', () => {
        if (isPixivHomepage()) {
            createBatchDownloadUI();
        } else {
            alert('请在Pixiv主页使用此功能');
            window.location.href = 'https://www.pixiv.net/';
        }
    });

    // 启动初始化
    init();
})();