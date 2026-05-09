  document.addEventListener('DOMContentLoaded', function() {
    const fetchBtn = document.getElementById('fetchBtn');
    const artworkIdInput = document.getElementById('artworkId');
    const pageInput = document.getElementById('page');
    const loading = document.getElementById('loading');
    const errorMessage = document.getElementById('errorMessage');
    const resultsSection = document.getElementById('resultsSection');
    const thumbnailImage = document.getElementById('thumbnailImage');
    const responseData = document.getElementById('responseData');
    const imageInfo = document.getElementById('imageInfo');

    fetchBtn.addEventListener('click', fetchThumbnail);

    // 按回车键触发获取
    artworkIdInput.addEventListener('keypress', function(e) {
      if (e.key === 'Enter') fetchThumbnail();
    });

    pageInput.addEventListener('keypress', function(e) {
      if (e.key === 'Enter') fetchThumbnail();
    });

    function fetchThumbnail() {
      const artworkId = artworkIdInput.value.trim();
      const page = pageInput.value.trim();

      if (!artworkId) {
        showError('请输入作品ID');
        return;
      }

      if (!page) {
        showError('请输入页码');
        return;
      }

      // 显示加载中
      loading.style.display = 'block';
      errorMessage.style.display = 'none';
      resultsSection.style.display = 'none';

      // 构建API URL
      const apiUrl = `/api/downloaded/thumbnail/${artworkId}/${page}`;

      // 发送请求
      fetch(apiUrl)
              .then(response => {
                if (!response.ok) {
                  if (response.status === 404) {
                    throw new Error('未找到指定的缩略图');
                  } else {
                    throw new Error(`HTTP错误: ${response.status}`);
                  }
                }
                return response.json();
              })
              .then(data => {
                // 处理响应数据
                displayResults(data);
              })
              .catch(error => {
                showError(error.message);
              })
              .finally(() => {
                loading.style.display = 'none';
              });
    }

    function displayResults(data) {
      // 显示响应数据
      responseData.textContent = JSON.stringify(data, null, 2);

      // 显示图片
      if (data.success && data.image) {
        // 假设image字段是Base64编码的图片数据
        // 根据扩展名设置正确的MIME类型
        const mimeType = getMimeType(data.extension);
        thumbnailImage.src = `data:${mimeType};base64,${data.image}`;
        thumbnailImage.alt = `作品 ${artworkIdInput.value} 第 ${pageInput.value} 页`;

        // 显示图片信息
        imageInfo.innerHTML = `
                        <div class="info-item">
                            <div class="info-label">扩展名</div>
                            <div class="info-value">${data.extension || '未知'}</div>
                        </div>
                        <div class="info-item">
                            <div class="info-label">文件大小</div>
                            <div class="info-value">${formatFileSize(data.fileSize)}</div>
                        </div>
                        <div class="info-item">
                            <div class="info-label">尺寸</div>
                            <div class="info-value">${data.width} × ${data.height}</div>
                        </div>
                        <div class="info-item">
                            <div class="info-label">请求状态</div>
                            <div class="info-value">${data.success ? '成功' : '失败'}</div>
                        </div>
                        <div class="info-item">
                            <div class="info-label">消息</div>
                            <div class="info-value">${data.message || '无'}</div>
                        </div>
                    `;
      } else {
        thumbnailImage.src = '';
        thumbnailImage.alt = '无可用图片';
        imageInfo.innerHTML = '<p>没有可用的图片数据</p>';
      }

      // 显示结果区域
      resultsSection.style.display = 'flex';
    }

    function showError(message) {
      errorMessage.textContent = message;
      errorMessage.style.display = 'block';
      resultsSection.style.display = 'none';
      loading.style.display = 'none';
    }

    function getMimeType(extension) {
      const mimeTypes = {
        'jpg': 'image/jpeg',
        'jpeg': 'image/jpeg',
        'png': 'image/png',
        'gif': 'image/gif',
        'webp': 'image/webp',
        'bmp': 'image/bmp'
      };

      return mimeTypes[extension?.toLowerCase()] || 'image/jpeg';
    }

    function formatFileSize(bytes) {
      if (!bytes) return '未知';

      if (bytes < 1024) {
        return bytes + ' B';
      } else if (bytes < 1024 * 1024) {
        return (bytes / 1024).toFixed(2) + ' KB';
      } else {
        return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
      }
    }
  });
