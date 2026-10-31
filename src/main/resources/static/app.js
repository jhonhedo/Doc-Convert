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
            hatchMode: 'none', // Disabled by default, keep only if explicitly selected
            targetLayers: 'PARKING, ROAD, ACCESS_WIDTH, COVERED_AREA',
            dpi: 150
        },
        currentPreviewItem: null,
        apiOnline: false
    };

    // Load saved settings
    try {
        const saved = localStorage.getItem('docconvert_settings_v2');
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

    const activeEngineBadge = document.getElementById('active-engine-badge');
    function updateHatchBadge() {
        if (!activeEngineBadge) return;
        if (state.settings.hatchMode === 'none') {
            activeEngineBadge.textContent = 'Hatch: OFF';
            activeEngineBadge.className = 'tag tag-muted';
        } else if (state.settings.hatchMode === 'all') {
            activeEngineBadge.textContent = 'Hatch: Force Solid';
            activeEngineBadge.className = 'tag tag-amber';
        } else {
            activeEngineBadge.textContent = 'Dynamic Hatch: ON';
            activeEngineBadge.className = 'tag tag-blue';
        }
    }
    updateHatchBadge();

    // Tools Navigation Dropdown
    const toolsDropdown = document.querySelector('.nav-dropdown');
    const toolsDropdownBtn = document.getElementById('tools-dropdown-btn') || document.querySelector('.nav-dropdown-toggle');
    if (toolsDropdown && toolsDropdownBtn) {
        toolsDropdownBtn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            const isOpen = toolsDropdown.classList.toggle('is-open');
            toolsDropdown.classList.toggle('open', isOpen);
            toolsDropdownBtn.setAttribute('aria-expanded', String(isOpen));
        });

        document.addEventListener('click', (e) => {
            if (!toolsDropdown.contains(e.target)) {
                toolsDropdown.classList.remove('is-open');
                toolsDropdown.classList.remove('open');
                toolsDropdownBtn.setAttribute('aria-expanded', 'false');
            }
        });

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && (toolsDropdown.classList.contains('is-open') || toolsDropdown.classList.contains('open'))) {
                toolsDropdown.classList.remove('is-open');
                toolsDropdown.classList.remove('open');
                toolsDropdownBtn.setAttribute('aria-expanded', 'false');
            }
        });
    }

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

        dropZone.addEventListener('click', (e) => {
            if (e.target.closest('button') || e.target.closest('label') || e.target.closest('a') || e.target.closest('input')) {
                return;
            }
            if (fileInput) fileInput.click();
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
            const isDxf = name.toLowerCase().endsWith('.dxf');
            const isDwg = name.toLowerCase().endsWith('.dwg');
            if (!isDxf && !isDwg) {
                alert(`"${name}" is not a supported CAD drawing. Please select a .dxf or .dwg file.`);
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

                const srcFormat = item.name.toLowerCase().endsWith('.dwg') ? 'DWG' : 'DXF';

                let nextStepsHtml = '';
                if (item.status === 'finished') {
                    nextStepsHtml = `
                        <div class="queue-item-chain">
                            <span class="chain-label">Next Drawing Set Action:</span>
                            <button type="button" class="btn btn-ghost btn-xs btn-chain-merge" data-id="${item.id}" title="Combine with other sheets into unified drawing set">
                                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:3px;"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>
                                Merge with other sheets
                            </button>
                            <button type="button" class="btn btn-ghost btn-xs btn-chain-resize" data-id="${item.id}" data-target="a1" title="Rescale to standard architectural A1 sheet">
                                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:3px;"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
                                Resize to A1
                            </button>
                            <button type="button" class="btn btn-ghost btn-xs btn-chain-stamp" data-id="${item.id}" title="Apply approval or status watermark stamp">
                                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:3px;"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
                                Add stamp
                            </button>
                            <button type="button" class="btn btn-ghost btn-xs btn-chain-compress" data-id="${item.id}" title="Compress underlays for municipal portal upload">
                                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:3px;"><polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" y1="10" x2="21" y2="3"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
                                Compress for portal upload
                            </button>
                        </div>
                    `;
                }

                row.innerHTML = `
                    <div class="queue-item-info">
                        <div class="queue-item-icon" aria-hidden="true">${srcFormat}</div>
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
                        <span class="format-pill dxf">${srcFormat}</span>
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
                        <span class="format-pill pdf">PDF</span>
                    </div>

                    <div class="queue-item-status-col">
                        ${statusBadgeHtml}
                    </div>

                    <div class="queue-item-actions">
                        ${actionButtonsHtml}
                    </div>
                    ${nextStepsHtml}
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
                    downloadBlob(item.pdfBlob, item.name.replace(/\.(dxf|dwg)$/i, '.pdf'));
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

        // Next Drawing Set Chains
        document.querySelectorAll('.btn-chain-merge').forEach(btn => {
            btn.addEventListener('click', async () => {
                const id = btn.getAttribute('data-id');
                const item = state.queue.find(it => it.id === id);
                if (item && item.pdfBlob) {
                    await CADHandoff.save(item.name.replace(/\.(dxf|dwg)$/i, '.pdf'), item.pdfBlob);
                    window.location.href = '/merge-pdf';
                }
            });
        });

        document.querySelectorAll('.btn-chain-resize').forEach(btn => {
            btn.addEventListener('click', async () => {
                const id = btn.getAttribute('data-id');
                const item = state.queue.find(it => it.id === id);
                if (item && item.pdfBlob) {
                    await CADHandoff.save(item.name.replace(/\.(dxf|dwg)$/i, '.pdf'), item.pdfBlob, { targetSize: btn.getAttribute('data-target') || 'a1' });
                    window.location.href = '/resize-pdf';
                }
            });
        });

        document.querySelectorAll('.btn-chain-stamp').forEach(btn => {
            btn.addEventListener('click', async () => {
                const id = btn.getAttribute('data-id');
                const item = state.queue.find(it => it.id === id);
                if (item && item.pdfBlob) {
                    await CADHandoff.save(item.name.replace(/\.(dxf|dwg)$/i, '.pdf'), item.pdfBlob);
                    window.location.href = '/stamp-pdf';
                }
            });
        });

        document.querySelectorAll('.btn-chain-compress').forEach(btn => {
            btn.addEventListener('click', async () => {
                const id = btn.getAttribute('data-id');
                const item = state.queue.find(it => it.id === id);
                if (item && item.pdfBlob) {
                    await CADHandoff.save(item.name.replace(/\.(dxf|dwg)$/i, '.pdf'), item.pdfBlob);
                    window.location.href = '/compress-pdf';
                }
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
            let readyItems = state.queue.filter(it => it.status === 'ready' || it.status === 'error');
            if (readyItems.length === 0 && state.queue.length > 0) {
                readyItems = state.queue; // Re-convert all items with updated settings
            }
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
            formData.append('hatchMode', state.settings.hatchMode);
            formData.append('layers', state.settings.hatchMode === 'none' ? '' : state.settings.targetLayers);
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
                    'X-Hatch-Mode': state.settings.hatchMode,
                    'X-Target-Layers': state.settings.hatchMode === 'none' ? '' : state.settings.targetLayers
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
                downloadBlob(finishedItems[0].pdfBlob, finishedItems[0].name.replace(/\.(dxf|dwg)$/i, '.pdf'));
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
                    const pdfName = it.name.replace(/\.(dxf|dwg)$/i, '.pdf');
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
        if (previewFilename) previewFilename.textContent = item.name.replace(/\.(dxf|dwg)$/i, '.pdf');
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
            formData.append('hatchMode', state.settings.hatchMode);
            formData.append('layers', state.settings.hatchMode === 'none' ? '' : state.settings.targetLayers);
            formData.append('dpi', state.settings.dpi);

            const res = await fetch('/api/preview', {
                method: 'POST',
                headers: {
                    'X-Hatch-Mode': state.settings.hatchMode,
                    'X-Target-Layers': state.settings.hatchMode === 'none' ? '' : state.settings.targetLayers
                },
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
                downloadBlob(state.currentPreviewItem.pdfBlob, state.currentPreviewItem.name.replace(/\.(dxf|dwg)$/i, '.pdf'));
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
            state.settings.hatchMode = optHatchMode ? optHatchMode.value : 'none';
            state.settings.targetLayers = optTargetLayers ? optTargetLayers.value.trim() : '';
            state.settings.dpi = optDpi ? parseInt(optDpi.value, 10) : 150;

            try {
                localStorage.setItem('docconvert_settings_v2', JSON.stringify(state.settings));
            } catch (e) {}

            updateHatchBadge();
            settingsModal.style.display = 'none';
        });
    }

    if (btnResetSettings) {
        btnResetSettings.addEventListener('click', () => {
            state.settings = {
                hatchMode: 'none',
                targetLayers: 'PARKING, ROAD, ACCESS_WIDTH, COVERED_AREA',
                dpi: 150
            };
            if (optHatchMode) optHatchMode.value = 'none';
            if (optTargetLayers) optTargetLayers.value = state.settings.targetLayers;
            if (optDpi) optDpi.value = '150';
            updateHatchBadge();
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

    // =========================================================================
    // PDF Merge Utility Logic
    // =========================================================================
    const mergeDropzone = document.getElementById('merge-dropzone');
    const mergeInput = document.getElementById('merge-input');
    const mergeAddMore = document.getElementById('merge-add-more');
    const mergeEmptyView = document.getElementById('merge-empty-view');
    const mergeQueueContainer = document.getElementById('merge-queue-container');
    const mergeFilesList = document.getElementById('merge-files-list');
    const mergeCount = document.getElementById('merge-count');
    const mergeTotalSize = document.getElementById('merge-total-size');
    const btnMergeClear = document.getElementById('btn-merge-clear');
    const btnExecuteMerge = document.getElementById('btn-execute-merge');

    let mergeQueue = [];

    function updateMergeUI() {
        if (!mergeQueueContainer || !mergeEmptyView) return;

        if (mergeQueue.length === 0) {
            mergeEmptyView.style.display = 'block';
            mergeQueueContainer.style.display = 'none';
            return;
        }

        mergeEmptyView.style.display = 'none';
        mergeQueueContainer.style.display = 'block';

        if (mergeCount) mergeCount.textContent = mergeQueue.length;
        const totalBytes = mergeQueue.reduce((acc, item) => acc + item.file.size, 0);
        if (mergeTotalSize) mergeTotalSize.textContent = formatBytes(totalBytes);

        if (mergeFilesList) {
            mergeFilesList.innerHTML = '';
            mergeQueue.forEach((item, index) => {
                const row = document.createElement('div');
                row.className = 'merge-file-row';
                row.innerHTML = `
                    <div class="merge-file-left">
                        <span class="merge-index-badge">${index + 1}</span>
                        <div>
                            <div class="merge-file-name" title="${escapeHtml(item.file.name)}">${escapeHtml(item.file.name)}</div>
                            <span class="merge-file-size">${formatBytes(item.file.size)}</span>
                        </div>
                    </div>
                    <div class="merge-file-actions">
                        <button type="button" class="btn-icon btn-move-up" data-idx="${index}" title="Move Up" ${index === 0 ? 'disabled' : ''}>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="18 15 12 9 6 15"/></svg>
                        </button>
                        <button type="button" class="btn-icon btn-move-down" data-idx="${index}" title="Move Down" ${index === mergeQueue.length - 1 ? 'disabled' : ''}>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
                        </button>
                        <button type="button" class="btn-icon danger btn-remove-merge" data-idx="${index}" title="Remove">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/></svg>
                        </button>
                    </div>
                `;
                mergeFilesList.appendChild(row);
            });

            // Bind reorder actions
            mergeFilesList.querySelectorAll('.btn-move-up').forEach(btn => {
                btn.addEventListener('click', () => {
                    const idx = parseInt(btn.getAttribute('data-idx'));
                    if (idx > 0) {
                        const temp = mergeQueue[idx];
                        mergeQueue[idx] = mergeQueue[idx - 1];
                        mergeQueue[idx - 1] = temp;
                        updateMergeUI();
                    }
                });
            });

            mergeFilesList.querySelectorAll('.btn-move-down').forEach(btn => {
                btn.addEventListener('click', () => {
                    const idx = parseInt(btn.getAttribute('data-idx'));
                    if (idx < mergeQueue.length - 1) {
                        const temp = mergeQueue[idx];
                        mergeQueue[idx] = mergeQueue[idx + 1];
                        mergeQueue[idx + 1] = temp;
                        updateMergeUI();
                    }
                });
            });

            mergeFilesList.querySelectorAll('.btn-remove-merge').forEach(btn => {
                btn.addEventListener('click', () => {
                    const idx = parseInt(btn.getAttribute('data-idx'));
                    mergeQueue.splice(idx, 1);
                    updateMergeUI();
                });
            });
        }
    }

    function addFilesToMerge(files) {
        for (let i = 0; i < files.length; i++) {
            const f = files[i];
            if (f.name.toLowerCase().endsWith('.pdf')) {
                mergeQueue.push({ id: Math.random().toString(36).substring(2, 9), file: f });
            }
        }
        updateMergeUI();
    }

    if (mergeInput) {
        mergeInput.addEventListener('change', (e) => {
            if (e.target.files) addFilesToMerge(e.target.files);
            mergeInput.value = '';
        });
    }

    if (mergeAddMore) {
        mergeAddMore.addEventListener('change', (e) => {
            if (e.target.files) addFilesToMerge(e.target.files);
            mergeAddMore.value = '';
        });
    }

    if (btnMergeClear) {
        btnMergeClear.addEventListener('click', () => {
            mergeQueue = [];
            updateMergeUI();
        });
    }

    if (mergeDropzone) {
        ['dragenter', 'dragover'].forEach(eventName => {
            mergeDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                mergeDropzone.classList.add('drag-active');
            });
        });
        ['dragleave', 'drop'].forEach(eventName => {
            mergeDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                mergeDropzone.classList.remove('drag-active');
            });
        });
        mergeDropzone.addEventListener('drop', (e) => {
            if (e.dataTransfer && e.dataTransfer.files) {
                addFilesToMerge(e.dataTransfer.files);
            }
        });
        mergeDropzone.addEventListener('click', (e) => {
            if (e.target.closest('button') || e.target.closest('label') || e.target.closest('a') || e.target.closest('input')) {
                return;
            }
            if (mergeInput) mergeInput.click();
        });
    }

    if (btnExecuteMerge) {
        btnExecuteMerge.addEventListener('click', async () => {
            if (mergeQueue.length < 2) {
                alert('Please select at least 2 PDF documents to merge.');
                return;
            }

            btnExecuteMerge.disabled = true;
            const origHtml = btnExecuteMerge.innerHTML;
            btnExecuteMerge.innerHTML = `<span class="spinner" style="width: 16px; height: 16px; border-width: 2px;"></span> Merging...`;

            try {
                const formData = new FormData();
                mergeQueue.forEach((item) => {
                    formData.append('file', item.file, item.file.name);
                });

                const res = await fetch('/api/pdf/merge', {
                    method: 'POST',
                    body: formData
                });

                if (!res.ok) {
                    const errJson = await res.json().catch(() => ({}));
                    throw new Error(errJson.error || 'Failed to merge documents.');
                }

                const blob = await res.blob();
                const downloadUrl = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = downloadUrl;
                a.download = 'merged_documents.pdf';
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                setTimeout(() => URL.revokeObjectURL(downloadUrl), 1000);

            } catch (err) {
                alert('Error: ' + err.message);
            } finally {
                btnExecuteMerge.disabled = false;
                btnExecuteMerge.innerHTML = origHtml;
            }
        });
    }

    // =========================================================================
    // PDF Compress Utility Logic
    // =========================================================================
    const compressDropzone = document.getElementById('compress-dropzone');
    const compressInput = document.getElementById('compress-input');
    const compressEmptyView = document.getElementById('compress-empty-view');
    const compressActiveContainer = document.getElementById('compress-active-container');
    const compressFileDisplay = document.getElementById('compress-file-display');
    const compressFileOrigSize = document.getElementById('compress-file-orig-size');
    const btnCompressRemove = document.getElementById('btn-compress-remove');
    const btnExecuteCompress = document.getElementById('btn-execute-compress');
    const compressResultBox = document.getElementById('compress-result-box');
    const resOrigSize = document.getElementById('res-orig-size');
    const resNewSize = document.getElementById('res-new-size');
    const resSavings = document.getElementById('res-savings');
    const btnDownloadCompressed = document.getElementById('btn-download-compressed');

    let currentCompressFile = null;
    let selectedCompressTier = 'medium';
    let lastCompressedBlob = null;
    let lastCompressedName = 'compressed.pdf';

    function setCompressFile(file) {
        if (!file || !file.name.toLowerCase().endsWith('.pdf')) {
            alert('Please select a valid PDF file.');
            return;
        }
        currentCompressFile = file;
        if (compressEmptyView) compressEmptyView.style.display = 'none';
        if (compressActiveContainer) compressActiveContainer.style.display = 'block';
        if (compressFileDisplay) compressFileDisplay.textContent = file.name;
        if (compressFileOrigSize) compressFileOrigSize.textContent = formatBytes(file.size);
        if (compressResultBox) compressResultBox.style.display = 'none';
    }

    if (compressInput) {
        compressInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files[0]) {
                setCompressFile(e.target.files[0]);
            }
            compressInput.value = '';
        });
    }

    if (btnCompressRemove) {
        btnCompressRemove.addEventListener('click', () => {
            currentCompressFile = null;
            if (compressEmptyView) compressEmptyView.style.display = 'block';
            if (compressActiveContainer) compressActiveContainer.style.display = 'none';
            if (compressResultBox) compressResultBox.style.display = 'none';
        });
    }

    if (compressDropzone) {
        ['dragenter', 'dragover'].forEach(eventName => {
            compressDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                compressDropzone.classList.add('drag-active');
            });
        });
        ['dragleave', 'drop'].forEach(eventName => {
            compressDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                compressDropzone.classList.remove('drag-active');
            });
        });
        compressDropzone.addEventListener('drop', (e) => {
            if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]) {
                setCompressFile(e.dataTransfer.files[0]);
            }
        });
        compressDropzone.addEventListener('click', (e) => {
            if (e.target.closest('button') || e.target.closest('label') || e.target.closest('a') || e.target.closest('input') || e.target.closest('.compress-tier-card')) {
                return;
            }
            if (compressInput) compressInput.click();
        });
    }

    document.querySelectorAll('.compress-tier-card').forEach(card => {
        card.addEventListener('click', () => {
            document.querySelectorAll('.compress-tier-card').forEach(c => c.classList.remove('selected'));
            card.classList.add('selected');
            selectedCompressTier = card.getAttribute('data-tier') || 'medium';
        });
    });

    if (btnExecuteCompress) {
        btnExecuteCompress.addEventListener('click', async () => {
            if (!currentCompressFile) return;

            btnExecuteCompress.disabled = true;
            const origHtml = btnExecuteCompress.innerHTML;
            btnExecuteCompress.innerHTML = `<span class="spinner" style="width: 16px; height: 16px; border-width: 2px;"></span> Compressing...`;

            try {
                const formData = new FormData();
                formData.append('file', currentCompressFile, currentCompressFile.name);
                formData.append('level', selectedCompressTier);

                const res = await fetch(`/api/pdf/compress?level=${selectedCompressTier}`, {
                    method: 'POST',
                    headers: { 'Accept': 'application/json' },
                    body: formData
                });

                if (!res.ok) {
                    const err = await res.json().catch(() => ({}));
                    throw new Error(err.error || 'PDF compression failed.');
                }

                const data = await res.json();
                lastCompressedName = data.filename || 'compressed.pdf';

                // Decode base64 to Blob
                const byteCharacters = atob(data.pdfBase64);
                const byteNumbers = new Array(byteCharacters.length);
                for (let i = 0; i < byteCharacters.length; i++) {
                    byteNumbers[i] = byteCharacters.charCodeAt(i);
                }
                const byteArray = new Uint8Array(byteNumbers);
                lastCompressedBlob = new Blob([byteArray], { type: 'application/pdf' });

                if (resOrigSize) resOrigSize.textContent = formatBytes(data.originalSize);
                if (resNewSize) resNewSize.textContent = formatBytes(data.compressedSize);
                const resultTitle = compressResultBox ? compressResultBox.querySelector('h4') : null;
                if (data.savingsPercentage > 0.0) {
                    if (resSavings) {
                        resSavings.textContent = `${data.savingsPercentage.toFixed(1)}%`;
                        resSavings.classList.add('savings');
                    }
                    if (resultTitle) resultTitle.textContent = 'Compression Completed Successfully!';
                } else {
                    if (resSavings) {
                        resSavings.textContent = 'Already Optimal';
                        resSavings.classList.remove('savings');
                    }
                    if (resultTitle) resultTitle.textContent = 'Document is already fully optimized!';
                }
                if (compressResultBox) compressResultBox.style.display = 'block';

            } catch (err) {
                alert('Error: ' + err.message);
            } finally {
                btnExecuteCompress.disabled = false;
                btnExecuteCompress.innerHTML = origHtml;
            }
        });
    }

    if (btnDownloadCompressed) {
        btnDownloadCompressed.addEventListener('click', () => {
            if (!lastCompressedBlob) return;
            const downloadUrl = URL.createObjectURL(lastCompressedBlob);
            const a = document.createElement('a');
            a.href = downloadUrl;
            a.download = lastCompressedName;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            setTimeout(() => URL.revokeObjectURL(downloadUrl), 1000);
        });
    }

    // =========================================================================
    // CAD Drawing Set Tool Handoff & Chaining System (Volatile In-Memory / IndexedDB)
    // =========================================================================
    const CADHandoff = {
        DB_NAME: 'cadconvert_handoff_store',
        STORE_NAME: 'handoff_item',
        async openDb() {
            return new Promise((resolve) => {
                if (!window.indexedDB) { resolve(null); return; }
                const req = indexedDB.open(CADHandoff.DB_NAME, 1);
                req.onupgradeneeded = (e) => {
                    const db = e.target.result;
                    if (!db.objectStoreNames.contains(CADHandoff.STORE_NAME)) {
                        db.createObjectStore(CADHandoff.STORE_NAME);
                    }
                };
                req.onsuccess = () => resolve(req.result);
                req.onerror = () => resolve(null);
            });
        },
        async save(name, blob, meta = {}) {
            try {
                const db = await this.openDb();
                if (db) {
                    await new Promise((resolve, reject) => {
                        const tx = db.transaction(CADHandoff.STORE_NAME, 'readwrite');
                        tx.objectStore(CADHandoff.STORE_NAME).put({ name, blob, meta, timestamp: Date.now() }, 'active');
                        tx.oncomplete = () => resolve();
                        tx.onerror = () => reject(tx.error);
                    });
                    return true;
                }
            } catch (e) {
                console.warn('IndexedDB save failed, attempting sessionStorage fallback', e);
            }

            // Fallback to sessionStorage for small PDFs
            if (blob && blob.size < 4 * 1024 * 1024) {
                try {
                    const reader = new FileReader();
                    reader.onloadend = () => {
                        try {
                            sessionStorage.setItem('cadconvert_handoff', JSON.stringify({
                                name,
                                dataUrl: reader.result,
                                meta
                            }));
                        } catch (e) {}
                    };
                    reader.readAsDataURL(blob);
                    return true;
                } catch (e) {}
            }
            return false;
        },
        async load() {
            try {
                const db = await this.openDb();
                if (db) {
                    const item = await new Promise((resolve) => {
                        const tx = db.transaction(CADHandoff.STORE_NAME, 'readonly');
                        const req = tx.objectStore(CADHandoff.STORE_NAME).get('active');
                        req.onsuccess = () => resolve(req.result);
                        req.onerror = () => resolve(null);
                    });
                    if (item && item.blob) {
                        try {
                            const txDel = db.transaction(CADHandoff.STORE_NAME, 'readwrite');
                            txDel.objectStore(CADHandoff.STORE_NAME).delete('active');
                        } catch (e) {}
                        return {
                            name: item.name,
                            file: new File([item.blob], item.name, { type: 'application/pdf' }),
                            blob: item.blob,
                            meta: item.meta || {}
                        };
                    }
                }
            } catch (e) {}

            try {
                const raw = sessionStorage.getItem('cadconvert_handoff');
                if (raw) {
                    sessionStorage.removeItem('cadconvert_handoff');
                    const data = JSON.parse(raw);
                    const res = await fetch(data.dataUrl);
                    const blob = await res.blob();
                    return {
                        name: data.name,
                        file: new File([blob], data.name, { type: 'application/pdf' }),
                        blob: blob,
                        meta: data.meta || {}
                    };
                }
            } catch (e) {}
            return null;
        }
    };

    function base64ToBlob(b64, mimeType = 'application/pdf') {
        const byteCharacters = atob(b64);
        const byteNumbers = new Array(byteCharacters.length);
        for (let i = 0; i < byteCharacters.length; i++) {
            byteNumbers[i] = byteCharacters.charCodeAt(i);
        }
        return new Blob([new Uint8Array(byteNumbers)], { type: mimeType });
    }

    // =========================================================================
    // PDF Split / Extract Utility Logic
    // =========================================================================
    const splitDropzone = document.getElementById('split-dropzone');
    const splitInput = document.getElementById('split-input');
    const splitEmptyView = document.getElementById('split-empty-view');
    const splitActiveContainer = document.getElementById('split-active-container');
    const splitFileDisplay = document.getElementById('split-file-display');
    const splitFileOrigSize = document.getElementById('split-file-orig-size');
    const btnSplitRemove = document.getElementById('btn-split-remove');
    const splitRangeInput = document.getElementById('split-range-input');
    const btnExecuteSplit = document.getElementById('btn-execute-split');
    const splitResultBox = document.getElementById('split-result-box');
    const resSplitCount = document.getElementById('res-split-count');
    const resSplitFormat = document.getElementById('res-split-format');
    const resSplitSize = document.getElementById('res-split-size');
    const btnDownloadSplit = document.getElementById('btn-download-split');

    let currentSplitFile = null;
    let selectedSplitMode = 'zip';
    let lastSplitBlob = null;
    let lastSplitFilename = 'split_sheets.zip';

    function setSplitFile(file) {
        if (!file || !file.name.toLowerCase().endsWith('.pdf')) {
            alert('Please select a valid PDF drawing set.');
            return;
        }
        currentSplitFile = file;
        if (splitEmptyView) splitEmptyView.style.display = 'none';
        if (splitActiveContainer) splitActiveContainer.style.display = 'block';
        if (splitFileDisplay) splitFileDisplay.textContent = file.name;
        if (splitFileOrigSize) splitFileOrigSize.textContent = formatBytes(file.size);
        if (splitResultBox) splitResultBox.style.display = 'none';
    }

    if (splitInput) {
        splitInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files[0]) {
                setSplitFile(e.target.files[0]);
            }
            splitInput.value = '';
        });
    }

    if (btnSplitRemove) {
        btnSplitRemove.addEventListener('click', () => {
            currentSplitFile = null;
            if (splitEmptyView) splitEmptyView.style.display = 'block';
            if (splitActiveContainer) splitActiveContainer.style.display = 'none';
            if (splitResultBox) splitResultBox.style.display = 'none';
        });
    }

    if (splitDropzone) {
        ['dragenter', 'dragover'].forEach(eventName => {
            splitDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                splitDropzone.classList.add('drag-active');
            });
        });
        ['dragleave', 'drop'].forEach(eventName => {
            splitDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                splitDropzone.classList.remove('drag-active');
            });
        });
        splitDropzone.addEventListener('drop', (e) => {
            if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]) {
                setSplitFile(e.dataTransfer.files[0]);
            }
        });
        splitDropzone.addEventListener('click', (e) => {
            if (e.target.closest('button') || e.target.closest('label') || e.target.closest('a') || e.target.closest('input') || e.target.closest('.compress-tier-card')) {
                return;
            }
            if (splitInput) splitInput.click();
        });
    }

    document.querySelectorAll('[data-split-mode]').forEach(card => {
        card.addEventListener('click', () => {
            document.querySelectorAll('[data-split-mode]').forEach(c => c.classList.remove('selected'));
            card.classList.add('selected');
            selectedSplitMode = card.getAttribute('data-split-mode') || 'zip';
        });
    });

    document.querySelectorAll('.range-pill').forEach(pill => {
        pill.addEventListener('click', () => {
            if (splitRangeInput) splitRangeInput.value = pill.getAttribute('data-range') || 'all';
        });
    });

    if (btnExecuteSplit) {
        btnExecuteSplit.addEventListener('click', async () => {
            if (!currentSplitFile) return;

            btnExecuteSplit.disabled = true;
            const origHtml = btnExecuteSplit.innerHTML;
            btnExecuteSplit.innerHTML = `<span class="spinner" style="width: 16px; height: 16px; border-width: 2px;"></span> Extracting...`;

            try {
                const formData = new FormData();
                formData.append('file', currentSplitFile, currentSplitFile.name);
                const rangeVal = splitRangeInput ? (splitRangeInput.value.trim() || 'all') : 'all';
                formData.append('pages', rangeVal);
                formData.append('mode', selectedSplitMode);

                const res = await fetch(`/api/pdf/split?pages=${encodeURIComponent(rangeVal)}&mode=${selectedSplitMode}`, {
                    method: 'POST',
                    headers: { 'Accept': 'application/json' },
                    body: formData
                });

                if (!res.ok) {
                    const err = await res.json().catch(() => ({}));
                    throw new Error(err.error || 'Failed to extract drawing set sheets.');
                }

                const data = await res.json();
                lastSplitFilename = data.filename || (selectedSplitMode === 'zip' ? 'split_sheets.zip' : 'extracted.pdf');
                lastSplitBlob = base64ToBlob(data.dataBase64, data.mimeType || (selectedSplitMode === 'zip' ? 'application/zip' : 'application/pdf'));

                if (resSplitCount) resSplitCount.textContent = `${data.extractedPages} of ${data.totalPages}`;
                if (resSplitFormat) resSplitFormat.textContent = selectedSplitMode === 'zip' ? 'ZIP Archive' : 'Vector PDF';
                if (resSplitSize) resSplitSize.textContent = formatBytes(data.fileSizeBytes);
                if (splitResultBox) splitResultBox.style.display = 'block';

            } catch (err) {
                alert('Error: ' + err.message);
            } finally {
                btnExecuteSplit.disabled = false;
                btnExecuteSplit.innerHTML = origHtml;
            }
        });
    }

    if (btnDownloadSplit) {
        btnDownloadSplit.addEventListener('click', () => {
            if (!lastSplitBlob) return;
            downloadBlob(lastSplitBlob, lastSplitFilename);
        });
    }

    // =========================================================================
    // PDF Resize / Rescale Utility Logic
    // =========================================================================
    const resizeDropzone = document.getElementById('resize-dropzone');
    const resizeInput = document.getElementById('resize-input');
    const resizeEmptyView = document.getElementById('resize-empty-view');
    const resizeActiveContainer = document.getElementById('resize-active-container');
    const resizeFileDisplay = document.getElementById('resize-file-display');
    const resizeFileOrigSize = document.getElementById('resize-file-orig-size');
    const btnResizeRemove = document.getElementById('btn-resize-remove');
    const resizeScaleMode = document.getElementById('resize-scale-mode');
    const resizeOrientation = document.getElementById('resize-orientation');
    const resizeCustomDims = document.getElementById('resize-custom-dimensions');
    const resizeCustomW = document.getElementById('resize-custom-w');
    const resizeCustomH = document.getElementById('resize-custom-h');
    const btnExecuteResize = document.getElementById('btn-execute-resize');
    const resizeResultBox = document.getElementById('resize-result-box');
    const resResizeSheet = document.getElementById('res-resize-sheet');
    const resResizeDims = document.getElementById('res-resize-dims');
    const resResizePages = document.getElementById('res-resize-pages');
    const btnDownloadResize = document.getElementById('btn-download-resize');

    let currentResizeFile = null;
    let selectedPaperSize = 'a3';
    let lastResizedBlob = null;
    let lastResizedFilename = 'resized_drawing.pdf';

    function setResizeFile(file, meta = {}) {
        if (!file || !file.name.toLowerCase().endsWith('.pdf')) {
            alert('Please select a valid PDF drawing set.');
            return;
        }
        currentResizeFile = file;
        if (resizeEmptyView) resizeEmptyView.style.display = 'none';
        if (resizeActiveContainer) resizeActiveContainer.style.display = 'block';
        if (resizeFileDisplay) resizeFileDisplay.textContent = file.name;
        if (resizeFileOrigSize) resizeFileOrigSize.textContent = formatBytes(file.size);
        if (resizeResultBox) resizeResultBox.style.display = 'none';

        if (meta && meta.targetSize) {
            selectedPaperSize = meta.targetSize.toLowerCase();
            document.querySelectorAll('[data-paper-size]').forEach(card => {
                const isMatch = (card.getAttribute('data-paper-size') || '').toLowerCase() === selectedPaperSize;
                card.classList.toggle('selected', isMatch);
            });
            if (resizeCustomDims) {
                resizeCustomDims.style.display = selectedPaperSize === 'custom' ? 'block' : 'none';
            }
        }
    }

    if (resizeInput) {
        resizeInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files[0]) {
                setResizeFile(e.target.files[0]);
            }
            resizeInput.value = '';
        });
    }

    if (btnResizeRemove) {
        btnResizeRemove.addEventListener('click', () => {
            currentResizeFile = null;
            if (resizeEmptyView) resizeEmptyView.style.display = 'block';
            if (resizeActiveContainer) resizeActiveContainer.style.display = 'none';
            if (resizeResultBox) resizeResultBox.style.display = 'none';
        });
    }

    if (resizeDropzone) {
        ['dragenter', 'dragover'].forEach(eventName => {
            resizeDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                resizeDropzone.classList.add('drag-active');
            });
        });
        ['dragleave', 'drop'].forEach(eventName => {
            resizeDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                resizeDropzone.classList.remove('drag-active');
            });
        });
        resizeDropzone.addEventListener('drop', (e) => {
            if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]) {
                setResizeFile(e.dataTransfer.files[0]);
            }
        });
        resizeDropzone.addEventListener('click', (e) => {
            if (e.target.closest('button') || e.target.closest('label') || e.target.closest('a') || e.target.closest('input') || e.target.closest('.compress-tier-card') || e.target.closest('select')) {
                return;
            }
            if (resizeInput) resizeInput.click();
        });
    }

    document.querySelectorAll('[data-paper-size]').forEach(card => {
        card.addEventListener('click', () => {
            document.querySelectorAll('[data-paper-size]').forEach(c => c.classList.remove('selected'));
            card.classList.add('selected');
            selectedPaperSize = card.getAttribute('data-paper-size') || 'a3';
            if (resizeCustomDims) {
                resizeCustomDims.style.display = selectedPaperSize === 'custom' ? 'block' : 'none';
            }
        });
    });

    if (btnExecuteResize) {
        btnExecuteResize.addEventListener('click', async () => {
            if (!currentResizeFile) return;

            btnExecuteResize.disabled = true;
            const origHtml = btnExecuteResize.innerHTML;
            btnExecuteResize.innerHTML = `<span class="spinner" style="width: 16px; height: 16px; border-width: 2px;"></span> Rescaling...`;

            try {
                const formData = new FormData();
                formData.append('file', currentResizeFile, currentResizeFile.name);
                formData.append('size', selectedPaperSize);
                const scaleVal = resizeScaleMode ? resizeScaleMode.value : 'fit';
                const orientVal = resizeOrientation ? resizeOrientation.value : 'auto';
                formData.append('scaleMode', scaleVal);
                formData.append('orientation', orientVal);

                if (selectedPaperSize === 'custom') {
                    formData.append('customWidth', resizeCustomW ? resizeCustomW.value : '1191');
                    formData.append('customHeight', resizeCustomH ? resizeCustomH.value : '842');
                }

                const res = await fetch(`/api/pdf/resize?size=${selectedPaperSize}&scaleMode=${scaleVal}&orientation=${orientVal}`, {
                    method: 'POST',
                    headers: { 'Accept': 'application/json' },
                    body: formData
                });

                if (!res.ok) {
                    const err = await res.json().catch(() => ({}));
                    throw new Error(err.error || 'Failed to resize drawing set.');
                }

                const data = await res.json();
                lastResizedFilename = data.filename || 'resized_drawing.pdf';
                lastResizedBlob = base64ToBlob(data.pdfBase64, 'application/pdf');

                if (resResizeSheet) resResizeSheet.textContent = selectedPaperSize.toUpperCase();
                if (resResizeDims) resResizeDims.textContent = `${Math.round(data.targetWidthPt)} × ${Math.round(data.targetHeightPt)} pt`;
                if (resResizePages) resResizePages.textContent = `${data.totalPages} Sheet(s)`;
                if (resizeResultBox) resizeResultBox.style.display = 'block';

            } catch (err) {
                alert('Error: ' + err.message);
            } finally {
                btnExecuteResize.disabled = false;
                btnExecuteResize.innerHTML = origHtml;
            }
        });
    }

    if (btnDownloadResize) {
        btnDownloadResize.addEventListener('click', () => {
            if (!lastResizedBlob) return;
            downloadBlob(lastResizedBlob, lastResizedFilename);
        });
    }

    // =========================================================================
    // PDF Rotate Utility Logic
    // =========================================================================
    const rotateDropzone = document.getElementById('rotate-dropzone');
    const rotateInput = document.getElementById('rotate-input');
    const rotateEmptyView = document.getElementById('rotate-empty-view');
    const rotateActiveContainer = document.getElementById('rotate-active-container');
    const rotateFileDisplay = document.getElementById('rotate-file-display');
    const rotateFileOrigSize = document.getElementById('rotate-file-orig-size');
    const btnRotateRemove = document.getElementById('btn-rotate-remove');
    const rotateRangeInput = document.getElementById('rotate-range-input');
    const btnExecuteRotate = document.getElementById('btn-execute-rotate');
    const rotateResultBox = document.getElementById('rotate-result-box');
    const resRotateAngle = document.getElementById('res-rotate-angle');
    const resRotateCount = document.getElementById('res-rotate-count');
    const resRotateTotal = document.getElementById('res-rotate-total');
    const btnDownloadRotate = document.getElementById('btn-download-rotate');

    let currentRotateFile = null;
    let selectedRotateAngle = 90;
    let lastRotatedBlob = null;
    let lastRotatedFilename = 'rotated_drawing.pdf';

    function setRotateFile(file) {
        if (!file || !file.name.toLowerCase().endsWith('.pdf')) {
            alert('Please select a valid PDF drawing set.');
            return;
        }
        currentRotateFile = file;
        if (rotateEmptyView) rotateEmptyView.style.display = 'none';
        if (rotateActiveContainer) rotateActiveContainer.style.display = 'block';
        if (rotateFileDisplay) rotateFileDisplay.textContent = file.name;
        if (rotateFileOrigSize) rotateFileOrigSize.textContent = formatBytes(file.size);
        if (rotateResultBox) rotateResultBox.style.display = 'none';
    }

    if (rotateInput) {
        rotateInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files[0]) {
                setRotateFile(e.target.files[0]);
            }
            rotateInput.value = '';
        });
    }

    if (btnRotateRemove) {
        btnRotateRemove.addEventListener('click', () => {
            currentRotateFile = null;
            if (rotateEmptyView) rotateEmptyView.style.display = 'block';
            if (rotateActiveContainer) rotateActiveContainer.style.display = 'none';
            if (rotateResultBox) rotateResultBox.style.display = 'none';
        });
    }

    if (rotateDropzone) {
        ['dragenter', 'dragover'].forEach(eventName => {
            rotateDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                rotateDropzone.classList.add('drag-active');
            });
        });
        ['dragleave', 'drop'].forEach(eventName => {
            rotateDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                rotateDropzone.classList.remove('drag-active');
            });
        });
        rotateDropzone.addEventListener('drop', (e) => {
            if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]) {
                setRotateFile(e.dataTransfer.files[0]);
            }
        });
        rotateDropzone.addEventListener('click', (e) => {
            if (e.target.closest('button') || e.target.closest('label') || e.target.closest('a') || e.target.closest('input') || e.target.closest('.compress-tier-card')) {
                return;
            }
            if (rotateInput) rotateInput.click();
        });
    }

    document.querySelectorAll('[data-rotate-angle]').forEach(card => {
        card.addEventListener('click', () => {
            document.querySelectorAll('[data-rotate-angle]').forEach(c => c.classList.remove('selected'));
            card.classList.add('selected');
            selectedRotateAngle = parseInt(card.getAttribute('data-rotate-angle') || '90', 10);
        });
    });

    document.querySelectorAll('.rotate-pill').forEach(pill => {
        pill.addEventListener('click', () => {
            if (rotateRangeInput) rotateRangeInput.value = pill.getAttribute('data-range') || 'all';
        });
    });

    if (btnExecuteRotate) {
        btnExecuteRotate.addEventListener('click', async () => {
            if (!currentRotateFile) return;

            btnExecuteRotate.disabled = true;
            const origHtml = btnExecuteRotate.innerHTML;
            btnExecuteRotate.innerHTML = `<span class="spinner" style="width: 16px; height: 16px; border-width: 2px;"></span> Rotating...`;

            try {
                const formData = new FormData();
                formData.append('file', currentRotateFile, currentRotateFile.name);
                formData.append('angle', selectedRotateAngle);
                const rangeVal = rotateRangeInput ? (rotateRangeInput.value.trim() || 'all') : 'all';
                formData.append('pages', rangeVal);

                const res = await fetch(`/api/pdf/rotate?angle=${selectedRotateAngle}&pages=${encodeURIComponent(rangeVal)}`, {
                    method: 'POST',
                    headers: { 'Accept': 'application/json' },
                    body: formData
                });

                if (!res.ok) {
                    const err = await res.json().catch(() => ({}));
                    throw new Error(err.error || 'Failed to rotate PDF sheets.');
                }

                const data = await res.json();
                lastRotatedFilename = data.filename || 'rotated_drawing.pdf';
                lastRotatedBlob = base64ToBlob(data.pdfBase64, 'application/pdf');

                if (resRotateAngle) resRotateAngle.textContent = `+${data.angle}°`;
                if (resRotateCount) resRotateCount.textContent = data.rotatedPagesCount;
                if (resRotateTotal) resRotateTotal.textContent = data.totalPages;
                if (rotateResultBox) rotateResultBox.style.display = 'block';

            } catch (err) {
                alert('Error: ' + err.message);
            } finally {
                btnExecuteRotate.disabled = false;
                btnExecuteRotate.innerHTML = origHtml;
            }
        });
    }

    if (btnDownloadRotate) {
        btnDownloadRotate.addEventListener('click', () => {
            if (!lastRotatedBlob) return;
            downloadBlob(lastRotatedBlob, lastRotatedFilename);
        });
    }

    // =========================================================================
    // PDF Stamp & Watermark Utility Logic
    // =========================================================================
    const stampDropzone = document.getElementById('stamp-dropzone');
    const stampInput = document.getElementById('stamp-input');
    const stampEmptyView = document.getElementById('stamp-empty-view');
    const stampActiveContainer = document.getElementById('stamp-active-container');
    const stampFileDisplay = document.getElementById('stamp-file-display');
    const stampFileOrigSize = document.getElementById('stamp-file-orig-size');
    const btnStampRemove = document.getElementById('btn-stamp-remove');
    const stampTextInput = document.getElementById('stamp-text-input');
    const stampPosition = document.getElementById('stamp-position');
    const stampColor = document.getElementById('stamp-color');
    const stampOpacitySlider = document.getElementById('stamp-opacity-slider');
    const stampOpacityVal = document.getElementById('stamp-opacity-val');
    const stampSizeSlider = document.getElementById('stamp-size-slider');
    const stampSizeVal = document.getElementById('stamp-size-val');
    const stampBorderCb = document.getElementById('stamp-border-cb');
    const stampRangeInput = document.getElementById('stamp-range-input');
    const btnExecuteStamp = document.getElementById('btn-execute-stamp');
    const stampResultBox = document.getElementById('stamp-result-box');
    const resStampText = document.getElementById('res-stamp-text');
    const resStampCount = document.getElementById('res-stamp-count');
    const resStampTotal = document.getElementById('res-stamp-total');
    const btnDownloadStamp = document.getElementById('btn-download-stamp');

    let currentStampFile = null;
    let lastStampedBlob = null;
    let lastStampedFilename = 'stamped_drawing.pdf';

    if (stampOpacitySlider && stampOpacityVal) {
        stampOpacitySlider.addEventListener('input', () => {
            stampOpacityVal.textContent = `${stampOpacitySlider.value}%`;
        });
    }

    if (stampSizeSlider && stampSizeVal) {
        stampSizeSlider.addEventListener('input', () => {
            stampSizeVal.textContent = `${stampSizeSlider.value} pt`;
        });
    }

    document.querySelectorAll('.stamp-preset-pill').forEach(pill => {
        pill.addEventListener('click', () => {
            if (stampTextInput) stampTextInput.value = pill.getAttribute('data-text') || 'FOR APPROVAL';
        });
    });

    function setStampFile(file) {
        if (!file || !file.name.toLowerCase().endsWith('.pdf')) {
            alert('Please select a valid PDF drawing set.');
            return;
        }
        currentStampFile = file;
        if (stampEmptyView) stampEmptyView.style.display = 'none';
        if (stampActiveContainer) stampActiveContainer.style.display = 'block';
        if (stampFileDisplay) stampFileDisplay.textContent = file.name;
        if (stampFileOrigSize) stampFileOrigSize.textContent = formatBytes(file.size);
        if (stampResultBox) stampResultBox.style.display = 'none';
    }

    if (stampInput) {
        stampInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files[0]) {
                setStampFile(e.target.files[0]);
            }
            stampInput.value = '';
        });
    }

    if (btnStampRemove) {
        btnStampRemove.addEventListener('click', () => {
            currentStampFile = null;
            if (stampEmptyView) stampEmptyView.style.display = 'block';
            if (stampActiveContainer) stampActiveContainer.style.display = 'none';
            if (stampResultBox) stampResultBox.style.display = 'none';
        });
    }

    if (stampDropzone) {
        ['dragenter', 'dragover'].forEach(eventName => {
            stampDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                stampDropzone.classList.add('drag-active');
            });
        });
        ['dragleave', 'drop'].forEach(eventName => {
            stampDropzone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                stampDropzone.classList.remove('drag-active');
            });
        });
        stampDropzone.addEventListener('drop', (e) => {
            if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]) {
                setStampFile(e.dataTransfer.files[0]);
            }
        });
        stampDropzone.addEventListener('click', (e) => {
            if (e.target.closest('button') || e.target.closest('label') || e.target.closest('a') || e.target.closest('input') || e.target.closest('select')) {
                return;
            }
            if (stampInput) stampInput.click();
        });
    }

    if (btnExecuteStamp) {
        btnExecuteStamp.addEventListener('click', async () => {
            if (!currentStampFile) return;

            btnExecuteStamp.disabled = true;
            const origHtml = btnExecuteStamp.innerHTML;
            btnExecuteStamp.innerHTML = `<span class="spinner" style="width: 16px; height: 16px; border-width: 2px;"></span> Applying Stamp...`;

            try {
                const formData = new FormData();
                formData.append('file', currentStampFile, currentStampFile.name);
                const textVal = stampTextInput ? (stampTextInput.value.trim() || 'FOR APPROVAL') : 'FOR APPROVAL';
                const posVal = stampPosition ? stampPosition.value : 'diagonal';
                const colVal = stampColor ? stampColor.value : '#DC2626';
                const opVal = stampOpacitySlider ? (parseInt(stampOpacitySlider.value, 10) / 100).toFixed(2) : '0.50';
                const sizeVal = stampSizeSlider ? stampSizeSlider.value : '36';
                const borderVal = stampBorderCb ? (stampBorderCb.checked ? 'true' : 'false') : 'true';
                const rangeVal = stampRangeInput ? (stampRangeInput.value.trim() || 'all') : 'all';

                formData.append('text', textVal);
                formData.append('position', posVal);
                formData.append('color', colVal);
                formData.append('opacity', opVal);
                formData.append('size', sizeVal);
                formData.append('border', borderVal);
                formData.append('pages', rangeVal);

                const res = await fetch(`/api/pdf/stamp?text=${encodeURIComponent(textVal)}&position=${posVal}&color=${encodeURIComponent(colVal)}&opacity=${opVal}&size=${sizeVal}&border=${borderVal}&pages=${encodeURIComponent(rangeVal)}`, {
                    method: 'POST',
                    headers: { 'Accept': 'application/json' },
                    body: formData
                });

                if (!res.ok) {
                    const err = await res.json().catch(() => ({}));
                    throw new Error(err.error || 'Failed to apply stamp to drawing set.');
                }

                const data = await res.json();
                lastStampedFilename = data.filename || 'stamped_drawing.pdf';
                lastStampedBlob = base64ToBlob(data.pdfBase64, 'application/pdf');

                if (resStampText) resStampText.textContent = data.stampText;
                if (resStampCount) resStampCount.textContent = data.stampedPagesCount;
                if (resStampTotal) resStampTotal.textContent = data.totalPages;
                if (stampResultBox) stampResultBox.style.display = 'block';

            } catch (err) {
                alert('Error: ' + err.message);
            } finally {
                btnExecuteStamp.disabled = false;
                btnExecuteStamp.innerHTML = origHtml;
            }
        });
    }

    if (btnDownloadStamp) {
        btnDownloadStamp.addEventListener('click', () => {
            if (!lastStampedBlob) return;
            downloadBlob(lastStampedBlob, lastStampedFilename);
        });
    }

    // =========================================================================
    // Auto-detect & Ingest Handoff PDF from Prior CAD Pipeline Step
    // =========================================================================
    async function initHandoff() {
        try {
            const handoff = await CADHandoff.load();
            if (!handoff || !handoff.file) return;

            if (splitDropzone && typeof setSplitFile === 'function') {
                setSplitFile(handoff.file);
            } else if (resizeDropzone && typeof setResizeFile === 'function') {
                setResizeFile(handoff.file, handoff.meta);
            } else if (rotateDropzone && typeof setRotateFile === 'function') {
                setRotateFile(handoff.file);
            } else if (stampDropzone && typeof setStampFile === 'function') {
                setStampFile(handoff.file);
            } else if (compressDropzone && typeof setCompressFile === 'function') {
                setCompressFile(handoff.file);
            } else if (mergeDropzone && typeof addFilesToMerge === 'function') {
                addFilesToMerge([handoff.file]);
            }
        } catch (e) {
            console.warn('CAD handoff ingestion error:', e);
        }
    }
    initHandoff();

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;')
                  .replace(/</g, '&lt;')
                  .replace(/>/g, '&gt;')
                  .replace(/"/g, '&quot;')
                  .replace(/'/g, '&#039;');
    }
});

