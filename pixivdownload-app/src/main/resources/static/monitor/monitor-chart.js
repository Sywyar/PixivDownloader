'use strict';
    // ===================== 图表 =====================
    function cssVar(name, fallback) {
        const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
        return value || fallback;
    }

    function updateChart(artworks) {
        const ctx = document.getElementById('downloadStatsChart').getContext('2d');
        const chartFill = cssVar('--chart-fill', 'rgba(0, 229, 255, 0.15)');
        const chartLine = cssVar('--chart-line', 'rgba(0, 229, 255, 0.8)');
        const chartHover = cssVar('--chart-hover', 'rgba(0, 229, 255, 0.3)');
        const chartGrid = cssVar('--chart-grid', 'rgba(0, 229, 255, 0.06)');
        const chartText = cssVar('--chart-text', '#475569');

        const monthly = {};
        artworks.forEach(a => {
            const d   = new Date(a.time);
            const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
            monthly[key] = (monthly[key] || 0) + a.count;
        });

        const labels = Object.keys(monthly).sort();
        const data   = labels.map(l => monthly[l]);

        if (downloadStatsChart) downloadStatsChart.destroy();

        downloadStatsChart = new Chart(ctx, {
            type: 'bar',
            data: {
                labels,
                datasets: [{
                    label: t('panel.monthly-images', 'Monthly Images'),
                    data,
                    backgroundColor: chartFill,
                    borderColor:     chartLine,
                    borderWidth: 1,
                    hoverBackgroundColor: chartHover,
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: {
                        beginAtZero: true,
                        grid: { color: chartGrid },
                        ticks: { color: chartText, font: { family: 'JetBrains Mono', size: 10 } },
                        border: { color: chartGrid }
                    },
                    x: {
                        grid: { color: chartGrid },
                        ticks: { color: chartText, font: { family: 'JetBrains Mono', size: 10 } },
                        border: { color: chartGrid }
                    }
                },
                plugins: {
                    legend: { labels: { color: chartText, font: { family: 'JetBrains Mono', size: 10 } } }
                }
            }
        });
    }
