// ==UserScript==
// @name         Pixiv作品批量下载器（N-Tab版）- 增强版
// @namespace    http://tampermonkey.net/
// @version      2.0
// @description  在Pixiv主页批量下载N-Tab中收藏的作品，支持暂停、间隔控制和实时进度显示
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

(function() {
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
                onload: function(response) {
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
                onerror: function(error) {
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
                onload: function(response) {
                    resolve(response.status === 200);
                },
                onerror: function() {
                    resolve(false);
                },
                ontimeout: function() {
                    resolve(false);
                }
            });
        });
    }

    // 获取下载状态
    async function getDownloadStatus(artworkId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'GET',
                url: `http://localhost:6999/api/download/status/${artworkId}`,
                onload: function(response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        resolve(data);
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

    // 取消下载
    async function cancelDownload(artworkId) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'POST',
                url: `http://localhost:6999/api/download/cancel/${artworkId}`,
                onload: function(response) {
                    try {
                        const data = JSON.parse(response.responseText);
                        resolve(data);
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
        <label style="font-size: 12px; margin-right: 10px; width: 120px;">状态检查间隔(秒):</label>
        <input type="number" id="status-check-interval" value="1" min="1" max="10" style="width: 60px; padding: 4px; border: 1px solid #ddd; border-radius: 4px;">
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

        const stopBtn = document.createElement('button');
        stopBtn.innerHTML = '⏹️ 停止下载';
        stopBtn.id = 'stop-batch-btn';
        stopBtn.style.cssText = `
            width: 100%;
            background: #dc3545;
            color: white;
            border: none;
            padding: 10px;
            border-radius: 5px;
            cursor: pointer;
            font-size: 14px;
        `;
        stopBtn.disabled = true;

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
        buttonContainer.appendChild(stopBtn);

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
        stopBtn.addEventListener('click', stopBatchDownload);

        // 检查后端状态
        checkBackendStatus().then(available => {
            const statusDiv = document.getElementById('batch-backend-status');
            if (statusDiv) {
                statusDiv.innerHTML = available ?
                    '✅ 后端服务可用' :
                '❌ 后端服务未启动';
                statusDiv.style.color = available ? '#28a745' : '#dc3545';
            }
        });

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
    let statusCheckIntervals = new Map();
    let currentArtworkId = null;

    // 解析输入数据
    function parseInputData() {
        const input = document.getElementById('ntab-data-input').value.trim();
        if (!input) {
            updateStatus('请输入N-Tab导出的数据', 'error');
            return;
        }

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
                    status: 'pending',
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
        document.getElementById('start-batch-btn').disabled = false;
        updateStats();

        // 保存队列
        saveQueueState();
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
                // 重要修复：确保进度计算正确
                const currentProgress = Math.max(artwork.currentImage || 0, 0);
                const progressPercent = Math.min(Math.round((currentProgress / artwork.totalImages) * 100), 100);

                progressHtml = `
        <div style="margin-top: 3px;">
            <div style="display: flex; justify-content: space-between; font-size: 9px; margin-bottom: 2px;">
                <span>进度: ${currentProgress}/${artwork.totalImages}</span>
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
            const progressPercent = Math.round((artwork.currentImage / artwork.totalImages) * 100);
            progressText = `
                <div style="margin-top: 5px;">
                    <div style="display: flex; justify-content: space-between; font-size: 10px; margin-bottom: 3px;">
                        <span>正在下载第 ${artwork.currentImage} 张 / 共 ${artwork.totalImages} 张</span>
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
            'failed': '失败'
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

        document.getElementById('start-batch-btn').disabled = true;
        document.getElementById('pause-batch-btn').disabled = false;
        document.getElementById('stop-batch-btn').disabled = false;

        updateStatus('开始批量下载...', 'info');
        updateStats();

        // 从上次停止的位置开始，或者从头开始
        if (currentDownloadIndex === -1) {
            currentDownloadIndex = 0;
        }

        // 获取最大并发数
        const maxConcurrent = parseInt(document.getElementById('max-concurrent').value) || 1;

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
            document.getElementById('stop-batch-btn').disabled = true;

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
        while (currentDownloadIndex < downloadQueue.length && isBatchRunning) {
            if (isBatchPaused) {
                // 如果暂停，等待恢复
                await new Promise(resolve => setTimeout(resolve, 500));
                continue;
            }

            const index = currentDownloadIndex++;
            if (index >= downloadQueue.length) break;

            const artwork = downloadQueue[index];

            if (artwork.status === 'pending' || artwork.status === 'failed') {
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
                    // 开始状态检查
                    startStatusCheck(artwork.id);

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
                    // 停止状态检查
                    stopStatusCheck(artwork.id);

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
    }

    // 暂停下载
    function pauseBatchDownload() {
        if (!isBatchRunning) return;

        isBatchPaused = true;
        document.getElementById('pause-batch-btn').disabled = true;
        document.getElementById('start-batch-btn').disabled = false;
        updateStatus('下载已暂停，等待当前作品完成...', 'warning');

        // 保存队列状态
        saveQueueState();
    }

    // 停止下载
    async function stopBatchDownload() {
        isBatchRunning = false;
        isBatchPaused = false;
        updateStatus('下载已停止，等待当前作品完成...', 'warning');
        document.getElementById('start-batch-btn').disabled = false;
        document.getElementById('pause-batch-btn').disabled = true;
        document.getElementById('stop-batch-btn').disabled = true;

        // 取消所有未开始的作品
        for (let i = currentDownloadIndex; i < downloadQueue.length; i++) {
            const artwork = downloadQueue[i];
            if (artwork.status === 'pending') {
                artwork.status = 'failed';
                failCount++;
            }
        }

        // 停止所有状态检查
        statusCheckIntervals.forEach((intervalId, artworkId) => {
            clearInterval(intervalId);
        });
        statusCheckIntervals.clear();

        // 更新显示
        updateQueueDisplay();
        updateStats();

        // 保存队列状态
        saveQueueState();
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

            // 发送下载请求到后端
            const response = await sendDownloadRequest(artwork.id, imageUrls);

            return response;

        } catch (error) {
            console.error(`下载作品 ${artwork.id} 失败:`, error);
            throw error;
        }
    }

    // 开始状态检查
    function startStatusCheck(artworkId) {
        // 清除已存在的检查
        stopStatusCheck(artworkId);

        const checkInterval = parseInt(document.getElementById('status-check-interval').value) || 2;
        const intervalMs = checkInterval * 1000;

        const intervalId = setInterval(async () => {
            try {
                const status = await getDownloadStatus(artworkId);
                if (status.success) {
                    // 更新作品状态显示
                    updateArtworkStatus(artworkId, status);

                    // 如果下载完成或失败，停止检查
                    if (status.completed || status.failed || status.cancelled) {
                        stopStatusCheck(artworkId);
                    }
                }
            } catch (error) {
                console.error(`检查作品 ${artworkId} 状态失败:`, error);
            }
        }, intervalMs);

        statusCheckIntervals.set(artworkId, intervalId);
    }

    // 停止状态检查
    function stopStatusCheck(artworkId) {
        const intervalId = statusCheckIntervals.get(artworkId);
        if (intervalId) {
            clearInterval(intervalId);
            statusCheckIntervals.delete(artworkId);
        }
    }

    // 更新作品状态
    function updateArtworkStatus(artworkId, status) {
        const artwork = downloadQueue.find(a => a.id === artworkId.toString());
        if (artwork) {
            // 更新当前下载的图片索引
            if (status.currentImageIndex !== undefined && status.currentImageIndex >= 0) {
                // 重要修复：使用 currentImageIndex + 1 来显示当前正在下载的图片
                artwork.currentImage = status.currentImageIndex + 1;
            }

            // 更新总图片数
            if (status.totalImages !== undefined) {
                artwork.totalImages = status.totalImages;
            }

            // 更新下载数量 - 使用 downloadedCount 作为实际完成的数量
            if (status.downloadedCount !== undefined) {
                // 重要修复：确保 downloadedCount 不会覆盖 currentImage
                // 这里我们保留 currentImage 作为当前正在处理的图片
                // downloadedCount 作为实际完成的图片数量
            }

            // 重要修复：如果正在下载中，确保状态是 downloading
            if (status.currentImageIndex >= 0 && !status.completed && !status.failed && !status.cancelled) {
                artwork.status = 'downloading';
            }

            // 更新队列显示和当前下载显示
            updateQueueDisplay();
            updateCurrentDownloadDisplay();

            // 更新状态信息
            if (status.completed) {
                artwork.status = 'completed';
                artwork.currentImage = status.downloadedCount || status.totalImages; // 完成后显示实际下载数量
                updateStatus(`✅ 完成: ${artwork.title} (${status.downloadedCount}/${status.totalImages})`, 'success');
            } else if (status.failed) {
                artwork.status = 'failed';
                updateStatus(`❌ 失败: ${artwork.title} - ${status.message}`, 'error');
            } else if (status.cancelled) {
                artwork.status = 'failed';
                updateStatus(`⏹️ 取消: ${artwork.title}`, 'warning');
            } else if (status.currentImageIndex >= 0) {
                // 重要修复：添加下载中的状态更新
                const currentImage = status.currentImageIndex + 1;
                const downloadedCount = status.downloadedCount || 0;
                updateStatus(`📥 下载中: ${artwork.title} (${currentImage}/${status.totalImages}) - 已完成 ${downloadedCount} 张`, 'info');
            }
        }
    }

    // 等待最终状态确认
    async function waitForFinalStatus(artworkId) {
        return new Promise((resolve) => {
            const checkStatus = async () => {
                try {
                    const status = await getDownloadStatus(artworkId);
                    if (status.success && (status.completed || status.failed || status.cancelled)) {
                        resolve(status);
                    } else {
                        setTimeout(checkStatus, 1000);
                    }
                } catch (error) {
                    // 如果查询失败，等待1秒后重试
                    setTimeout(checkStatus, 1000);
                }
            };

            checkStatus();
        });
    }

    // 保存队列状态
    function saveQueueState() {
        const queueState = {
            queue: downloadQueue,
            currentIndex: currentDownloadIndex,
            completedCount: completedCount,
            successCount: successCount,
            failCount: failCount,
            activeDownloads: activeDownloads,
            timestamp: new Date().toISOString()
        };

        GM_setValue('batchDownloadQueue', queueState);
    }

    // 加载保存的队列
    function loadSavedQueue() {
        const savedState = GM_getValue('batchDownloadQueue', null);

        if (savedState && savedState.queue && savedState.queue.length > 0) {
            downloadQueue = savedState.queue;
            currentDownloadIndex = savedState.currentIndex || 0;
            completedCount = savedState.completedCount || 0;
            successCount = savedState.successCount || 0;
            failCount = savedState.failCount || 0;
            activeDownloads = savedState.activeDownloads || 0;

            updateQueueDisplay();
            updateStats();
            document.getElementById('start-batch-btn').disabled = false;

            const pendingCount = downloadQueue.filter(a => a.status === 'pending' || a.status === 'failed').length;
            if (pendingCount > 0) {
                updateStatus(`发现未完成的下载队列 (${pendingCount}个作品待下载)`, 'info');
            }
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