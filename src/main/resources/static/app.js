/**
 * DocConvert - Frontend Application Logic
 * DXF to Vector PDF Converter
 */

document.addEventListener('DOMContentLoaded', () => {
    // 25 MB Client-side upload safety threshold
    const MAX_UPLOAD_BYTES = 25 * 1024 * 1024;

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
        const saved = localStorage.getItem('docconvert_settings') || localStorage.getItem('cadconvert_settings');
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

    // Legal Modals
    const privacyModal = document.getElementById('privacy-modal');
    const termsModal = document.getElementById('terms-modal');
    const linkPrivacy = document.getElementById('link-privacy');
    const linkTerms = document.getElementById('link-terms');
    const btnClosePrivacy = document.getElementById('btn-close-privacy');
    const btnAgreePrivacy = document.getElementById('btn-agree-privacy');
    const btnCloseTerms = document.getElementById('btn-close-terms');
    const btnAgreeTerms = document.getElementById('btn-agree-terms');

    // API Docs Section
    const apiTabs = document.querySelectorAll('.api-tab');
    const apiCodeContent = document.getElementById('api-code-content');
    const btnCopyCode = document.getElementById('btn-copy-code');
    const btnOpenApi = document.getElementById('btn-open-api');

    // Populate Settings UI from saved state
    if (optHatchMode) optHatchMode.value = state.settings.hatchMode;
    if (optTargetLayers) optTargetLayers.value = state.settings.targetLayers;
    if (optDpi) optDpi.value = String(state.settings.dpi);

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
                if (statusBadge) {
                    const dot = statusBadge.querySelector('.status-dot');
                    if (dot) dot.style.backgroundColor = '#10B981';
                }
            } else {
                throw new Error('Non-200');
            }
        } catch (err) {
            state.apiOnline = false;
            statusText.textContent = 'Service Standby';
            if (statusBadge) {
                const dot = statusBadge.querySelector('.status-dot');
                if (dot) dot.style.backgroundColor = '#F59E0B';
            }
        }
    }
    checkServerHealth();
    setInterval(checkServerHealth, 30000);

    // =========================================================================
    // 2. Drag and Drop & File Upload Handling
    // =========================================================================
    if (dropZone) {
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
    }

    if (fileInput) {
        fileInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files.length > 0) {
                handleSelectedFiles(e.target.files);
                fileInput.value = '';
            }
        });
    }

    if (addMoreInput) {
        addMoreInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files.length > 0) {
                handleSelectedFiles(e.target.files);
                addMoreInput.value = '';
            }
        });
    }

    // Sample CAD File Loader
    if (btnLoadSample) {
        btnLoadSample.addEventListener('click', async () => {
            try {
                btnLoadSample.disabled = true;
                btnLoadSample.innerHTML = `<span>Loading Sample CAD...</span>`;
                const res = await fetch('/api/sample');
                if (!res.ok) throw new Error('Failed to load sample CAD');
                const blob = await res.blob();
                const sampleFile = new File([blob], 'architectural_floorplan_sample.dxf', { type: 'application/dxf' });
                handleSelectedFiles([sampleFile]);
            } catch (err) {
                alert('Could not fetch sample DXF: ' + err.message);
            } finally {
                btnLoadSample.disabled = false;
                btnLoadSample.innerHTML = `
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg>
                    <span>Load Sample CAD File</span>
                `;
            }
        });
    }

    function handleSelectedFiles(files) {
        let addedCount = 0;
        for (let i = 0; i < files.length; i++) {
            const file = files[i];
            const name = file.name || 'unnamed.dxf';
            if (!name.toLowerCase().endsWith('.dxf')) {
                alert(`"${name}" is not a DXF file. Only .dxf drawings are supported.`);
                continue;
            }

            if (file.size > MAX_UPLOAD_BYTES) {
                alert(`"${name}" exceeds the maximum upload limit of 25 MB (${(file.size / (1024 * 1024)).toFixed(1)} MB). Please select a file under 25 MB.`);
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
        if (!bytes || bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    }

    function renderQueue() {
        if (state.queue.length === 0) {
            if (dropzoneView) dropzoneView.style.display = 'flex';
            if (queueView) queueView.style.display = 'none';
            return;
        }

        if (dropzoneView) dropzoneView.style.display = 'none';
        if (queueView) queueView.style.display = 'block';

        if (queueCount) queueCount.textContent = state.queue.length;
        const totalBytes = state.queue.reduce((acc, it) => acc + (it.size || 0), 0);
        if (queueTotalSize) queueTotalSize.textContent = formatBytes(totalBytes);

        // Check if any are finished
        const anyFinished = state.queue.some(it => it.status === 'finished');
        if (btnDownloadAll) btnDownloadAll.style.display = anyFinished ? 'inline-flex' : 'none';

        // Update queue list HTML
        if (queueList) {
            queueList.innerHTML = '';
            state.queue.forEach((item) => {
                const row = document.createElement('div');
                row.className = 'queue-item';
                row.id = `row-${item.id}`;
                row.setAttribute('role', 'listitem');

                let statusBadgeHtml = '';
                if (item.status === 'ready') {
                    statusBadgeHtml = `<span class="status-badge ready">READY</span>`;
                } else if (item.status === 'converting') {
                    statusBadgeHtml = `<span class="status-badge converting"><span class="spinner" style="width:12px;height:12px;border-width:2px;" aria-hidden="true"></span> CONVERTING</span>`;
                } else if (item.status === 'finished') {
                    statusBadgeHtml = `<span class="status-badge finished">FINISHED</span>`;
                } else if (item.status === 'error') {
                    statusBadgeHtml = `<span class="status-badge error" title="${escapeHtml(item.error || 'Conversion Error')}">FAILED</span>`;
                }

                let actionButtonsHtml = '';
                if (item.status === 'ready') {
                    actionButtonsHtml = `
                        <button class="btn btn-primary btn-sm btn-item-convert" data-id="${item.id}" aria-label="Convert ${escapeHtml(item.name)}">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14M7 23l-4-4 4-4"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg>
                            <span>Convert</span>
                        </button>
                        <button class="btn btn-ghost btn-sm text-danger btn-item-remove" data-id="${item.id}" aria-label="Remove ${escapeHtml(item.name)} from queue">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                        </button>
                    `;
                } else if (item.status === 'converting') {
                    actionButtonsHtml = `
                        <span class="text-muted" style="font-size:0.8rem; font-family:var(--font-mono);">${item.progress}%</span>
                    `;
                } else if (item.status === 'finished') {
                    actionButtonsHtml = `
                        <button class="btn btn-secondary btn-sm btn-item-preview" data-id="${item.id}" aria-label="Preview converted ${escapeHtml(item.name)}">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                            <span>Preview</span>
                        </button>
                        <button class="btn btn-success btn-sm btn-item-download" data-id="${item.id}" aria-label="Download converted ${escapeHtml(item.name)}">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/></svg>
                            <span>Download</span>
                        </button>
                        <button class="btn btn-ghost btn-sm text-danger btn-item-remove" data-id="${item.id}" aria-label="Remove ${escapeHtml(item.name)} from list">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                        </button>
                    `;
                } else if (item.status === 'error') {
                    actionButtonsHtml = `
                        <button class="btn btn-secondary btn-sm btn-item-convert" data-id="${item.id}" aria-label="Retry converting ${escapeHtml(item.name)}">
                            <span>Retry</span>
                        </button>
                        <button class="btn btn-ghost btn-sm text-danger btn-item-remove" data-id="${item.id}" aria-label="Remove failed file">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                        </button>
                    `;
                }

                row.innerHTML = `
                    <div class="queue-item-info">
                        <div class="queue-item-icon" aria-hidden="true">CAD</div>
                        <div class="queue-item-meta">
                            <span class="queue-item-name" title="${escapeHtml(item.name)}">${escapeHtml(item.name)}</span>
                            <span class="queue-item-size">${formatBytes(item.size)}</span>
                            ${item.status === 'converting' ? `
                                <div class="item-progress-bar" role="progressbar" aria-valuenow="${item.progress}" aria-valuemin="0" aria-valuemax="100">
                                    <div class="item-progress-fill" style="width: ${item.progress}%;"></div>
                                </div>
                            ` : ''}
                            ${item.status === 'error' && item.error ? `
                                <span class="text-danger" style="font-size:0.75rem; margin-top:2px;">${escapeHtml(item.error)}</span>
                            ` : ''}
                        </div>
                    </div>

                    <div class="queue-item-transition" aria-hidden="true">
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
        }

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

    if (btnClearQueue) {
        btnClearQueue.addEventListener('click', () => {
            state.queue = [];
            renderQueue();
        });
    }

    // =========================================================================
    // 4. Conversion Pipeline
    // =========================================================================
    if (btnConvertAll) {
        btnConvertAll.addEventListener('click', async () => {
            const readyItems = state.queue.filter(it => it.status === 'ready' || it.status === 'error');
            if (readyItems.length === 0) return;

            btnConvertAll.disabled = true;
            for (const item of readyItems) {
                await convertItem(item);
            }
            btnConvertAll.disabled = false;
        });
    }

    async function convertItem(item) {
        item.status = 'converting';
        item.progress = 25;
        item.error = null;
        renderQueue();

        try {
            const formData = new FormData();
            formData.append('file', item.file, item.name);
            formData.append('filename', item.name);
            formData.append('layers', state.settings.targetLayers);
            formData.append('format', 'json'); // Request JSON with both PDF and preview base64
            formData.append('dpi', state.settings.dpi);

            item.progress = 50;
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
                let errMsg = `Server returned HTTP ${response.status}`;
                try {
                    const errJson = await response.json();
                    if (errJson && errJson.error) {
                        errMsg = errJson.error;
                    }
                } catch (e) {
                    try {
                        const errText = await response.text();
                        if (errText) errMsg = errText;
                    } catch (ignored) {}
                }
                throw new Error(errMsg);
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
            item.status = 'error';
            item.error = err.message || 'Conversion failed';
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
        setTimeout(() => URL.revokeObjectURL(url), 2000);
    }

    // Batch Download All as ZIP
    if (btnDownloadAll) {
        btnDownloadAll.addEventListener('click', async () => {
            const finishedItems = state.queue.filter(it => it.status === 'finished' && it.pdfBlob);
            if (finishedItems.length === 0) return;

            if (finishedItems.length === 1) {
                downloadBlob(finishedItems[0].pdfBlob, finishedItems[0].name.replace(/\.dxf$/i, '.pdf'));
                return;
            }

            try {
                btnDownloadAll.disabled = true;
                btnDownloadAll.innerHTML = `<span>Creating ZIP...</span>`;

                if (typeof JSZip === 'undefined') {
                    throw new Error('JSZip library not loaded');
                }

                const zip = new JSZip();
                finishedItems.forEach(it => {
                    const pdfName = it.name.replace(/\.dxf$/i, '.pdf');
                    zip.file(pdfName, it.pdfBlob);
                });

                const zipBlob = await zip.generateAsync({ type: 'blob' });
                downloadBlob(zipBlob, `docconvert_${Date.now()}.zip`);
            } catch (err) {
                alert('Failed to generate batch ZIP: ' + err.message);
            } finally {
                btnDownloadAll.disabled = false;
                btnDownloadAll.innerHTML = `
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/></svg>
                    <span>Download All (ZIP)</span>
                `;
            }
        });
    }

    // =========================================================================
    // 5. Preview Modal Operations
    // =========================================================================
    function openPreviewModal(item) {
        state.currentPreviewItem = item;
        if (previewFilename) previewFilename.textContent = item.name.replace(/\.dxf$/i, '.pdf');
        if (previewModal) previewModal.style.display = 'flex';

        if (item.previewB64) {
            if (previewImg) {
                previewImg.src = 'data:image/png;base64,' + item.previewB64;
                previewImg.style.display = 'block';
            }
            if (previewLoader) previewLoader.style.display = 'none';
        } else {
            // Fetch preview thumbnail on demand
            if (previewImg) previewImg.style.display = 'none';
            if (previewLoader) previewLoader.style.display = 'flex';
            fetchPreviewOnDemand(item);
        }
    }

    async function fetchPreviewOnDemand(item) {
        try {
            const formData = new FormData();
            formData.append('file', item.file, item.name);
            formData.append('layers', state.settings.targetLayers);
            formData.append('dpi', state.settings.dpi);

            const res = await fetch('/api/preview', {
                method: 'POST',
                body: formData
            });

            if (!res.ok) throw new Error('Preview generation failed');
            const blob = await res.blob();
            const previewUrl = URL.createObjectURL(blob);

            if (previewImg) {
                previewImg.src = previewUrl;
                previewImg.style.display = 'block';
            }
            if (previewLoader) previewLoader.style.display = 'none';
        } catch (e) {
            if (previewLoader) {
                previewLoader.innerHTML = `<p class="text-danger">Preview unavailable for this CAD drawing.</p>`;
            }
        }
    }

    if (btnClosePreview && previewModal) {
        btnClosePreview.addEventListener('click', () => {
            previewModal.style.display = 'none';
        });
    }

    if (btnPreviewDownload) {
        btnPreviewDownload.addEventListener('click', () => {
            if (state.currentPreviewItem && state.currentPreviewItem.pdfBlob) {
                downloadBlob(state.currentPreviewItem.pdfBlob, state.currentPreviewItem.name.replace(/\.dxf$/i, '.pdf'));
            }
        });
    }

    // =========================================================================
    // 6. Settings Modal Operations
    // =========================================================================
    if (btnGlobalSettings && settingsModal) {
        btnGlobalSettings.addEventListener('click', () => {
            if (optHatchMode) optHatchMode.value = state.settings.hatchMode;
            if (optTargetLayers) optTargetLayers.value = state.settings.targetLayers;
            if (optDpi) optDpi.value = String(state.settings.dpi);
            settingsModal.style.display = 'flex';
        });
    }

    if (btnCloseSettings && settingsModal) {
        btnCloseSettings.addEventListener('click', () => {
            settingsModal.style.display = 'none';
        });
    }

    if (btnSaveSettings && settingsModal) {
        btnSaveSettings.addEventListener('click', () => {
            state.settings.hatchMode = optHatchMode ? optHatchMode.value : 'default';
            state.settings.targetLayers = optTargetLayers ? optTargetLayers.value.trim() : '';
            state.settings.dpi = optDpi ? parseInt(optDpi.value, 10) : 150;

            try {
                localStorage.setItem('docconvert_settings', JSON.stringify(state.settings));
            } catch (e) {}

            const activeEngineBadge = document.getElementById('active-engine-badge');
            if (activeEngineBadge) {
                if (state.settings.hatchMode === 'none') {
                    activeEngineBadge.textContent = 'Dynamic Hatch: OFF';
                    activeEngineBadge.className = 'tag tag-muted';
                } else if (state.settings.hatchMode === 'all') {
                    activeEngineBadge.textContent = 'Hatch: Force Solid';
                    activeEngineBadge.className = 'tag tag-amber';
                } else {
                    activeEngineBadge.textContent = 'Dynamic Hatch: ON';
                    activeEngineBadge.className = 'tag tag-blue';
                }
            }

            settingsModal.style.display = 'none';
        });
    }

    if (btnResetSettings) {
        btnResetSettings.addEventListener('click', () => {
            state.settings = {
                hatchMode: 'default',
                targetLayers: 'PARKING, ROAD, ACCESS_WIDTH, COVERED_AREA',
                dpi: 150
            };
            if (optHatchMode) optHatchMode.value = 'default';
            if (optTargetLayers) optTargetLayers.value = state.settings.targetLayers;
            if (optDpi) optDpi.value = '150';
        });
    }

    // =========================================================================
    // 7. Legal Modals (Privacy & Terms)
    // =========================================================================
    if (linkPrivacy && privacyModal) {
        linkPrivacy.addEventListener('click', (e) => {
            e.preventDefault();
            privacyModal.style.display = 'flex';
        });
        [btnClosePrivacy, btnAgreePrivacy].forEach(btn => {
            if (btn) btn.addEventListener('click', () => { privacyModal.style.display = 'none'; });
        });
    }

    if (linkTerms && termsModal) {
        linkTerms.addEventListener('click', (e) => {
            e.preventDefault();
            termsModal.style.display = 'flex';
        });
        [btnCloseTerms, btnAgreeTerms].forEach(btn => {
            if (btn) btn.addEventListener('click', () => { termsModal.style.display = 'none'; });
        });
    }

    // Backdrop clicks to close modals
    [settingsModal, previewModal, privacyModal, termsModal].forEach(modal => {
        if (modal) {
            modal.addEventListener('click', (e) => {
                if (e.target === modal) modal.style.display = 'none';
            });
        }
    });

    // Escape key to dismiss any open modal
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            [settingsModal, previewModal, privacyModal, termsModal].forEach(m => {
                if (m) m.style.display = 'none';
            });
        }
    });

    // =========================================================================
    // 8. API Integration Code Generator
    // =========================================================================
    const codeSnippets = {
        curl: `curl -X POST "https://docconvert.net/api/convert" \\
  -H "X-Filename: drawing.dxf" \\
  --data-binary @"drawing.dxf" \\
  --output "drawing.pdf"`,

        js: `// Convert DXF to Vector PDF in JavaScript (Browser / Node)
const formData = new FormData();
formData.append('file', dxfFile, dxfFile.name);
formData.append('layers', 'PARKING,ROAD,COVERED_AREA');

const response = await fetch('https://docconvert.net/api/convert', {
  method: 'POST',
  body: formData
});

if (!response.ok) throw new Error('Conversion failed');
const pdfBlob = await response.blob();
const downloadUrl = URL.createObjectURL(pdfBlob);
window.open(downloadUrl);`,

        java: `// Convert DXF to PDF in Java 11+
HttpClient client = HttpClient.newHttpClient();
byte[] dxfBytes = Files.readAllBytes(Paths.get("drawing.dxf"));

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://docconvert.net/api/convert"))
    .header("Content-Type", "application/octet-stream")
    .header("X-Filename", "drawing.dxf")
    .header("X-Target-Layers", "PARKING,ROAD")
    .POST(HttpRequest.BodyPublishers.ofByteArray(dxfBytes))
    .build();

HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
Files.write(Paths.get("drawing.pdf"), response.body());`,

        docker: `# Run DocConvert Server in Docker (Port 8080)
docker build -t docconvert:latest .
docker run -d -p 8080:8080 -e MAX_UPLOAD_SIZE_MB=25 --name docconvert docconvert:latest

# Health check
curl http://localhost:8080/api/health`
    };

    apiTabs.forEach(tab => {
        tab.addEventListener('click', () => {
            apiTabs.forEach(t => {
                t.classList.remove('active');
                t.setAttribute('aria-selected', 'false');
            });
            tab.classList.add('active');
            tab.setAttribute('aria-selected', 'true');
            const key = tab.getAttribute('data-tab');
            if (codeSnippets[key] && apiCodeContent) {
                apiCodeContent.querySelector('code').textContent = codeSnippets[key];
            }
        });
    });

    if (btnCopyCode && apiCodeContent) {
        btnCopyCode.addEventListener('click', () => {
            const text = apiCodeContent.querySelector('code').textContent;
            navigator.clipboard.writeText(text).then(() => {
                const orig = btnCopyCode.textContent;
                btnCopyCode.textContent = 'Copied!';
                setTimeout(() => { btnCopyCode.textContent = orig; }, 2000);
            });
        });
    }

    if (btnOpenApi) {
        btnOpenApi.addEventListener('click', () => {
            const apiSection = document.getElementById('api-section');
            if (apiSection) apiSection.scrollIntoView({ behavior: 'smooth' });
        });
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;')
                  .replace(/</g, '&lt;')
                  .replace(/>/g, '&gt;')
                  .replace(/"/g, '&quot;')
                  .replace(/'/g, '&#039;');
    }
});
