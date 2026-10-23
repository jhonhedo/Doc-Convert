/**
 * CADConvert - Frontend Application Logic
 * DXF to PDF Converter inspired by CloudConvert
 */

document.addEventListener('DOMContentLoaded', () => {
    // State
    const state = {
        queue: [], // Array of { id, file, name, size, status, progress, pdfBlob, previewB64, error }
        settings: {
            hatchMode: 'default',
            targetLayers: 'PARKING, ROAD, ACCESS_WIDTH, COVERED_AREA',
            dpi: 150
        },
        currentPreviewItem: null,
        apiOnline: false
    };

    // Load saved settings
    try {
        const saved = localStorage.getItem('cadconvert_settings');
        if (saved) {
            state.settings = Object.assign(state.settings, JSON.parse(saved));
        }
    } catch (e) {}

    // DOM Elements
    const dropZone = document.getElementById('drop-zone');
    const fileInput = document.getElementById('file-input');
    const addMoreInput = document.getElementById('add-more-input');
    const dropzoneView = document.getElementById('dropzone-view');
    const queueView = document.getElementById('queue-view');
    const queueList = document.getElementById('queue-list');
    const queueCount = document.getElementById('queue-count');
    const queueTotalSize = document.getElementById('queue-total-size');
    const btnClearQueue = document.getElementById('btn-clear-queue');
    const btnConvertAll = document.getElementById('btn-convert-all');
    const btnDownloadAll = document.getElementById('btn-download-all');
    const btnLoadSample = document.getElementById('btn-load-sample');
    const btnGlobalSettings = document.getElementById('btn-global-settings');

    const statusBadge = document.getElementById('server-status-badge');
    const statusText = document.getElementById('status-text');

    // Settings Modal
    const settingsModal = document.getElementById('settings-modal');
    const btnCloseSettings = document.getElementById('btn-close-settings');
    const btnSaveSettings = document.getElementById('btn-save-settings');
    const btnResetSettings = document.getElementById('btn-reset-settings');
    const optHatchMode = document.getElementById('opt-hatch-mode');
    const optTargetLayers = document.getElementById('opt-target-layers');
    const optDpi = document.getElementById('opt-dpi');

    // Preview Modal
    const previewModal = document.getElementById('preview-modal');
    const btnClosePreview = document.getElementById('btn-close-preview');
    const previewImg = document.getElementById('preview-img');
    const previewLoader = document.getElementById('preview-loader');
    const previewFilename = document.getElementById('preview-filename');
    const btnPreviewDownload = document.getElementById('btn-preview-download');

    // API Docs Section
    const apiTabs = document.querySelectorAll('.api-tab');
    const apiCodeContent = document.getElementById('api-code-content');
    const btnCopyCode = document.getElementById('btn-copy-code');
    const btnOpenApi = document.getElementById('btn-open-api');

    // =========================================================================
    // 1. Server Health Check & Heartbeat
    // =========================================================================
    async function checkServerHealth() {
        try {
            const start = performance.now();
            const res = await fetch('/api/health');
            if (res.ok) {
                const data = await res.json();
                const latency = Math.round(performance.now() - start);
                state.apiOnline = true;
                statusText.textContent = `Online (${latency}ms)`;
                statusBadge.querySelector('.status-dot').style.backgroundColor = '#10B981';
            } else {
                throw new Error('Non-200');
            }
        } catch (err) {
            state.apiOnline = false;
            statusText.textContent = 'Server Offline';
            statusBadge.querySelector('.status-dot').style.backgroundColor = '#EF4444';
        }
    }
    checkServerHealth();
    setInterval(checkServerHealth, 15000);

    // =========================================================================
    // 2. Drag and Drop & File Upload Handling
    // =========================================================================
    ['dragenter', 'dragover'].forEach(eventName => {
        dropZone.addEventListener(eventName, (e) => {
            e.preventDefault();
            e.stopPropagation();
            dropZone.classList.add('drag-over');
        });
    });

    ['dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, (e) => {
            e.preventDefault();
            e.stopPropagation();
            dropZone.classList.remove('drag-over');
        });
    });

    dropZone.addEventListener('drop', (e) => {
        const files = e.dataTransfer.files;
        if (files && files.length > 0) {
            handleSelectedFiles(files);
        }
    });

    fileInput.addEventListener('change', (e) => {
        if (e.target.files && e.target.files.length > 0) {
            handleSelectedFiles(e.target.files);
            fileInput.value = '';
        }
    });

    addMoreInput.addEventListener('change', (e) => {
        if (e.target.files && e.target.files.length > 0) {
            handleSelectedFiles(e.target.files);
            addMoreInput.value = '';
        }
    });

    // Sample CAD File Loader
    btnLoadSample.addEventListener('click', async () => {
        try {
            btnLoadSample.disabled = true;
            btnLoadSample.innerHTML = `<span>Loading Sample CAD...</span>`;
            const res = await fetch('/api/sample');
            if (!res.ok) throw new Error('Failed to load sample');
            const blob = await res.blob();
            const sampleFile = new File([blob], 'architectural_floorplan_sample.dxf', { type: 'application/dxf' });
            handleSelectedFiles([sampleFile]);
        } catch (err) {
            alert('Could not fetch sample DXF from server: ' + err.message);
        } finally {
            btnLoadSample.disabled = false;
            btnLoadSample.innerHTML = `
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg>
                <span>Load Sample CAD File</span>
            `;
        }
    });

    function handleSelectedFiles(files) {
        let addedCount = 0;
        for (let i = 0; i < files.length; i++) {
            const file = files[i];
            const name = file.name || 'unnamed.dxf';
            if (!name.toLowerCase().endsWith('.dxf')) {
                alert(`"${name}" is not a DXF file. Only .dxf files are supported.`);
                continue;
            }

            const item = {
                id: 'dxf_' + Date.now() + '_' + Math.random().toString(36).substr(2, 7),
                file: file,
                name: name,
                size: file.size,
                status: 'ready', // 'ready', 'converting', 'finished', 'error'
                progress: 0,
                pdfBlob: null,
                previewB64: null,
                error: null
            };

            state.queue.push(item);
            addedCount++;
        }

        if (addedCount > 0) {
            renderQueue();
        }
    }

    // =========================================================================
    // 3. Queue Rendering & Operations
    // =========================================================================
    function formatBytes(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    }

    function renderQueue() {
        if (state.queue.length === 0) {
            dropzoneView.style.display = 'flex';
            queueView.style.display = 'none';
            return;
        }

        dropzoneView.style.display = 'none';
        queueView.style.display = 'block';

        queueCount.textContent = state.queue.length;
        const totalBytes = state.queue.reduce((acc, it) => acc + (it.size || 0), 0);
        queueTotalSize.textContent = formatBytes(totalBytes);

        // Check if any are finished
        const anyFinished = state.queue.some(it => it.status === 'finished');
        btnDownloadAll.style.display = anyFinished ? 'inline-flex' : 'none';

        // Update queue list HTML
        queueList.innerHTML = '';
        state.queue.forEach((item) => {
            const row = document.createElement('div');
            row.className = 'queue-item';
            row.id = `row-${item.id}`;

            let statusBadgeHtml = '';
            if (item.status === 'ready') {
                statusBadgeHtml = `<span class="status-badge ready">READY</span>`;
            } else if (item.status === 'converting') {
                statusBadgeHtml = `<span class="status-badge converting"><span class="spinner" style="width:12px;height:12px;border-width:2px;"></span> CONVERTING</span>`;
            } else if (item.status === 'finished') {
                statusBadgeHtml = `<span class="status-badge finished">FINISHED</span>`;
            } else if (item.status === 'error') {
                statusBadgeHtml = `<span class="status-badge error" title="${escapeHtml(item.error || 'Error')}">FAILED</span>`;
            }

            let actionButtonsHtml = '';
            if (item.status === 'ready') {
                actionButtonsHtml = `
                    <button class="btn btn-primary btn-sm btn-item-convert" data-id="${item.id}">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14M7 23l-4-4 4-4"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg>
                        <span>Convert</span>
                    </button>
                    <button class="btn btn-ghost btn-sm text-danger btn-item-remove" data-id="${item.id}">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                `;
            } else if (item.status === 'converting') {
                actionButtonsHtml = `
                    <span class="text-muted" style="font-size:0.8rem; font-family:var(--font-mono);">${item.progress}%</span>
                `;
            } else if (item.status === 'finished') {
                actionButtonsHtml = `
                    <button class="btn btn-secondary btn-sm btn-item-preview" data-id="${item.id}">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                        <span>Preview</span>
                    </button>
                    <button class="btn btn-success btn-sm btn-item-download" data-id="${item.id}">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/></svg>
                        <span>Download</span>
                    </button>
                    <button class="btn btn-ghost btn-sm text-danger btn-item-remove" data-id="${item.id}">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                `;
            } else if (item.status === 'error') {
                actionButtonsHtml = `
                    <button class="btn btn-secondary btn-sm btn-item-convert" data-id="${item.id}">
                        <span>Retry</span>
                    </button>
                    <button class="btn btn-ghost btn-sm text-danger btn-item-remove" data-id="${item.id}">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                `;
            }

            row.innerHTML = `
                <div class="queue-item-info">
                    <div class="queue-item-icon">CAD</div>
                    <div class="queue-item-meta">
                        <span class="queue-item-name" title="${escapeHtml(item.name)}">${escapeHtml(item.name)}</span>
                        <span class="queue-item-size">${formatBytes(item.size)}</span>
                        ${item.status === 'converting' ? `
                            <div class="item-progress-bar">
                                <div class="item-progress-fill" style="width: ${item.progress}%;"></div>
                            </div>
                        ` : ''}
                    </div>
                </div>

                <div class="queue-item-transition">
                    <span class="format-pill dxf">DXF</span>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
                    <span class="format-pill pdf">PDF</span>
                </div>

                <div class="queue-item-status-col">
                    ${statusBadgeHtml}
                </div>

                <div class="queue-item-actions">
                    ${actionButtonsHtml}
                </div>
            `;

            queueList.appendChild(row);
        });

        attachItemEvents();
    }

    function attachItemEvents() {
        // Individual Convert
        document.querySelectorAll('.btn-item-convert').forEach(btn => {
            btn.addEventListener('click', () => {
                const id = btn.getAttribute('data-id');
                const item = state.queue.find(it => it.id === id);
                if (item) convertItem(item);
            });
        });

        // Individual Remove
        document.querySelectorAll('.btn-item-remove').forEach(btn => {
            btn.addEventListener('click', () => {
                const id = btn.getAttribute('data-id');
                state.queue = state.queue.filter(it => it.id !== id);
                renderQueue();
            });
        });

        // Individual Download
        document.querySelectorAll('.btn-item-download').forEach(btn => {
            btn.addEventListener('click', () => {
                const id = btn.getAttribute('data-id');
                const item = state.queue.find(it => it.id === id);
                if (item && item.pdfBlob) {
                    downloadBlob(item.pdfBlob, item.name.replace(/\.dxf$/i, '.pdf'));
                }
            });
        });

        // Individual Preview
        document.querySelectorAll('.btn-item-preview').forEach(btn => {
            btn.addEventListener('click', () => {
                const id = btn.getAttribute('data-id');
                const item = state.queue.find(it => it.id === id);
                if (item) openPreviewModal(item);
            });
        });
    }

    btnClearQueue.addEventListener('click', () => {
        state.queue = [];
        renderQueue();
    });

    // =========================================================================
    // 4. Conversion Pipeline
    // =========================================================================
    btnConvertAll.addEventListener('click', async () => {
        const readyItems = state.queue.filter(it => it.status === 'ready' || it.status === 'error');
        if (readyItems.length === 0) return;

        btnConvertAll.disabled = true;
        for (const item of readyItems) {
            await convertItem(item);
        }
        btnConvertAll.disabled = false;
    });

    async function convertItem(item) {
        item.status = 'converting';
        item.progress = 25;
        renderQueue();

        try {
            const formData = new FormData();
            formData.append('file', item.file, item.name);
            formData.append('filename', item.name);
            formData.append('layers', state.settings.targetLayers);
            formData.append('format', 'json'); // Request JSON with both PDF and preview base64
            formData.append('dpi', state.settings.dpi);

            item.progress = 55;
            renderQueue();

            const response = await fetch('/api/convert', {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                    'X-Output-Format': 'json',
                    'X-Filename': item.name,
                    'X-Target-Layers': state.settings.targetLayers
                },
                body: formData
            });

            if (!response.ok) {
                const errText = await response.text();
                throw new Error(errText || `Server responded with ${response.status}`);
            }

            item.progress = 85;
            renderQueue();

            const contentType = response.headers.get('content-type') || '';
            if (contentType.includes('application/json')) {
                const result = await response.json();
                if (!result.success) throw new Error(result.error || 'Conversion failed');

                // Decode base64 PDF
                const binaryStr = atob(result.pdfBase64);
                const bytes = new Uint8Array(binaryStr.length);
                for (let i = 0; i < binaryStr.length; i++) {
                    bytes[i] = binaryStr.charCodeAt(i);
                }
                item.pdfBlob = new Blob([bytes], { type: 'application/pdf' });
                item.previewB64 = result.previewBase64;
            } else {
                // Direct binary PDF stream
                item.pdfBlob = await response.blob();
            }

            item.progress = 100;
            item.status = 'finished';
        } catch (err) {
            console.error('Conversion error:', err);
            item.status = 'error';
            item.error = err.message;
        }

        renderQueue();
    }

    function downloadBlob(blob, filename) {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    // Batch Download All as ZIP
    btnDownloadAll.addEventListener('click', async () => {
        const finishedItems = state.queue.filter(it => it.status === 'finished' && it.pdfBlob);
        if (finishedItems.length === 0) return;

        if (finishedItems.length === 1) {
            downloadBlob(finishedItems[0].pdfBlob, finishedItems[0].name.replace(/\.dxf$/i, '.pdf'));
            return;
        }

        try {
            btnDownloadAll.disabled = true;
            btnDownloadAll.innerHTML = `<span>Building ZIP...</span>`;

            if (typeof JSZip === 'undefined') {
                // Fallback: download each sequentially
                finishedItems.forEach(it => downloadBlob(it.pdfBlob, it.name.replace(/\.dxf$/i, '.pdf')));
                return;
            }

            const zip = new JSZip();
            finishedItems.forEach(it => {
                const pdfName = it.name.replace(/\.dxf$/i, '.pdf');
                zip.file(pdfName, it.pdfBlob);
            });

            const content = await zip.generateAsync({ type: 'blob' });
            downloadBlob(content, 'cadconvert_batch_drawings.zip');
        } catch (err) {
            alert('Failed to generate ZIP: ' + err.message);
        } finally {
            btnDownloadAll.disabled = false;
            btnDownloadAll.innerHTML = `
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/></svg>
                <span>Download All (ZIP)</span>
            `;
        }
    });

    // =========================================================================
    // 5. Preview Modal
    // =========================================================================
    async function openPreviewModal(item) {
        state.currentPreviewItem = item;
        const pdfName = item.name.replace(/\.dxf$/i, '.pdf');
        previewFilename.textContent = pdfName;
        previewModal.style.display = 'flex';

        if (item.previewB64 && item.previewB64.length > 0) {
            previewImg.src = 'data:image/png;base64,' + item.previewB64;
            previewImg.style.display = 'block';
            previewLoader.style.display = 'none';
        } else {
            // Fetch preview on the fly
            previewImg.style.display = 'none';
            previewLoader.style.display = 'flex';

            try {
                const formData = new FormData();
                formData.append('file', item.file, item.name);
                formData.append('layers', state.settings.targetLayers);
                formData.append('dpi', state.settings.dpi);

                const res = await fetch('/api/preview', {
                    method: 'POST',
                    body: formData
                });

                if (!res.ok) throw new Error('Preview rendering failed');
                const blob = await res.blob();
                previewImg.src = URL.createObjectURL(blob);
                previewImg.style.display = 'block';
                previewLoader.style.display = 'none';
            } catch (err) {
                previewLoader.innerHTML = `<span class="text-danger">Failed to generate visual preview: ${escapeHtml(err.message)}</span>`;
            }
        }
    }

    btnClosePreview.addEventListener('click', () => {
        previewModal.style.display = 'none';
        state.currentPreviewItem = null;
    });

    btnPreviewDownload.addEventListener('click', () => {
        if (state.currentPreviewItem && state.currentPreviewItem.pdfBlob) {
            downloadBlob(
                state.currentPreviewItem.pdfBlob,
                state.currentPreviewItem.name.replace(/\.dxf$/i, '.pdf')
            );
        }
    });

    // =========================================================================
    // 6. Settings Modal
    // =========================================================================
    btnGlobalSettings.addEventListener('click', () => {
        optHatchMode.value = state.settings.hatchMode;
        optTargetLayers.value = state.settings.targetLayers;
        optDpi.value = state.settings.dpi;
        settingsModal.style.display = 'flex';
    });

    btnCloseSettings.addEventListener('click', () => {
        settingsModal.style.display = 'none';
    });

    btnSaveSettings.addEventListener('click', () => {
        state.settings.hatchMode = optHatchMode.value;
        state.settings.targetLayers = optTargetLayers.value;
        state.settings.dpi = parseInt(optDpi.value) || 150;

        try {
            localStorage.setItem('cadconvert_settings', JSON.stringify(state.settings));
        } catch (e) {}

        const badge = document.getElementById('active-engine-badge');
        if (badge) {
            badge.textContent = state.settings.hatchMode === 'none' ? 'Hatch: OFF' : 'Dynamic Hatch: ON';
        }

        settingsModal.style.display = 'none';
    });

    btnResetSettings.addEventListener('click', () => {
        optHatchMode.value = 'default';
        optTargetLayers.value = 'PARKING, ROAD, ACCESS_WIDTH, COVERED_AREA';
        optDpi.value = '150';
    });

    // Close modals on escape key or backdrop click
    window.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            settingsModal.style.display = 'none';
            previewModal.style.display = 'none';
        }
    });

    [settingsModal, previewModal].forEach(modal => {
        modal.addEventListener('click', (e) => {
            if (e.target === modal) {
                modal.style.display = 'none';
            }
        });
    });

    // =========================================================================
    // 7. Developer API Tabs
    // =========================================================================
    const codeSnippets = {
        curl: `curl -X POST "http://localhost:8080/api/convert" \\
  -H "X-Filename: drawing.dxf" \\
  -H "X-Target-Layers: PARKING,ROAD,COVERED_AREA" \\
  --data-binary @"drawing.dxf" \\
  --output "drawing.pdf"`,

        js: `// Convert DXF to PDF in JavaScript
const dxfFile = document.querySelector('input[type="file"]').files[0];
const formData = new FormData();
formData.append('file', dxfFile, dxfFile.name);
formData.append('layers', 'PARKING,ROAD,COVERED_AREA');

const response = await fetch('http://localhost:8080/api/convert', {
  method: 'POST',
  body: formData
});

const pdfBlob = await response.blob();
const downloadUrl = URL.createObjectURL(pdfBlob);
window.open(downloadUrl);`,

        java: `// Convert DXF to PDF in Java 11+
HttpClient client = HttpClient.newHttpClient();
byte[] dxfBytes = Files.readAllBytes(Paths.get("drawing.dxf"));

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:8080/api/convert"))
    .header("Content-Type", "application/octet-stream")
    .header("X-Filename", "drawing.dxf")
    .header("X-Target-Layers", "PARKING,ROAD")
    .POST(HttpRequest.BodyPublishers.ofByteArray(dxfBytes))
    .build();

HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
Files.write(Paths.get("drawing.pdf"), response.body());`,

        docker: `# Run CADConvert Web Server in Docker (Port 8080)
docker build -t cadconvert:latest .
docker run -d -p 8080:8080 --name cadconvert-server cadconvert:latest

# Health check
curl http://localhost:8080/api/health`
    };

    apiTabs.forEach(tab => {
        tab.addEventListener('click', () => {
            apiTabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            const key = tab.getAttribute('data-tab');
            if (codeSnippets[key]) {
                apiCodeContent.querySelector('code').textContent = codeSnippets[key];
            }
        });
    });

    btnCopyCode.addEventListener('click', () => {
        const text = apiCodeContent.querySelector('code').textContent;
        navigator.clipboard.writeText(text).then(() => {
            const orig = btnCopyCode.textContent;
            btnCopyCode.textContent = 'Copied!';
            setTimeout(() => { btnCopyCode.textContent = orig; }, 2000);
        });
    });

    btnOpenApi.addEventListener('click', () => {
        const apiSection = document.getElementById('api-section');
        if (apiSection) apiSection.scrollIntoView({ behavior: 'smooth' });
    });

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;')
                  .replace(/</g, '&lt;')
                  .replace(/>/g, '&gt;')
                  .replace(/"/g, '&quot;')
                  .replace(/'/g, '&#039;');
    }
});
