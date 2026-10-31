# CADConvert Product Scope & Boundary Guardrails

## 1. Product Identity & Core Mission
**CADConvert** is a **CAD-first** engineering utility suite. Its primary mission is high-fidelity, clean-room conversion of AutoCAD drawings (DXF and DWG) into vector PDF documents, backed by a tightly focused collection of **Drawing Set Tools** designed to prepare architectural and engineering drawing sets for submittal, municipal permitting, and archival.

CADConvert is **not** a general-purpose PDF editor.

---

## 2. In Scope

### A. Core CAD Conversion
- **AutoCAD DXF & DWG to Vector PDF**:
  - Full vector fidelity (lines, arcs, polylines, circles, splines, ellipses, dimensions, text, MText, hatches, solid fills).
  - Model space and paper space layouts.
  - CAD layer visibility control, line weight mapping, and true RGB/ACI color palettes.
  - Architectural pattern generation and hatch rule engine.
  - In-memory processing with zero permanent server-side disk storage.

### B. Drawing Set Tools (Secondary Workflow Utilities)
These utilities exist exclusively to service multi-sheet CAD deliverables and permit submittals:
1. **Combine Multi-Sheet Sets (Merge PDF)**:
   - Concatenate individual CAD sheet outputs into a unified multi-page drawing set.
   - Retain mixed sheet sizes (A4 through A0 / custom) and vector operators.
2. **Split & Extract Sheets (Split PDF)**:
   - Extract individual discipline drawings or plan sheets by range (e.g. `1-3,5`, `odd`, `even`).
   - Export extracted sheets as a ZIP bundle or consolidated multi-page PDF.
3. **Resize & Rescale Plot Sizes (Resize PDF)**:
   - Fit or scale drawing sheets to standard sheet formats (A0, A1, A2, A3, A4) or custom plot sizes.
   - Vector transform modes (`FIT`, `FILL`, `KEEP_SCALE`) without rasterization.
4. **Rotate Drawing Sheets (Rotate PDF)**:
   - 90°, 180°, and 270° orientation alignment for title blocks and landscape/portrait plans.
5. **Approval & Watermark Stamp (Stamp PDF)**:
   - Vector typography stamps ("FOR APPROVAL", "PRELIMINARY", "ISSUED FOR PERMIT", date macros `{date}`, sheet indices `{sheet}`).
   - Opacity, border box, rotation, and standard corner/diagonal positioning.
6. **Meet Municipal Portal Upload Limits (Compress PDF)**:
   - Target embedded high-resolution raster underlays (site aerials, scan overlays) with 3-tier DPI downsampling.
   - 100% preservation of all CAD vector geometry and linework.

---

## 3. Strictly Out of Scope

The following capabilities are explicitly **excluded** from CADConvert to prevent product bloat, security sprawl, and deviation from our core engineering audience:

| Out-of-Scope Capability | Rationale |
| :--- | :--- |
| **In-place PDF Text & Content Editing** | Complex, prone to font reflow bugs, and fundamentally outside CAD workflow needs. Architects edit in CAD, not in post-export PDFs. |
| **PDF Form Creation & Form Field Filling** | AcroForms / XFA forms are administrative features, not engineering plan requirements. |
| **Optical Character Recognition (OCR)** | CAD drawings originate digitally; CADConvert outputs crisp vector text. OCR introduces heavy native dependencies (Tesseract) and high memory usage. |
| **Office & Document Conversions** | DOCX, XLSX, PPTX, or general image conversions belong in generic office converters, diluting CADConvert's technical branding. |
| **PDF Signing / PKI Digital Certificates** | Submittal portals typically handle signing separately; digital signature standards vary widely by jurisdiction and introduce crypto liability. |
| **General Graphic Manipulation / Annotations** | Redlining, markup pencils, freehand drawing, and shapes are handled by specialized review suites (Bluebeam, BIM 360). |

---

## 4. Criteria & Justification Required to Revisit Scope

Before any out-of-scope feature is reconsidered, all of the following evidentiary criteria must be met:

1. **Direct User Inquiries / Ticket Volume**:
   - At least 15% of inbound user feedback or enterprise support inquiries explicitly request the specific feature in connection with CAD drawing submission workflows.
2. **Search Demand & Customer Intent**:
   - Measurable organic search traffic showing users actively seeking that feature specifically within the CAD domain (e.g., "sign CAD drawing set", not "free online pdf editor").
3. **Regulatory / Portal Compliance Mandates**:
   - Official municipal permitting portal guidelines (e.g., NYC DOB NOW, Chicago E-Plan, Florida Building Code portals) requiring a specific capability as a condition of submittal acceptance.
4. **Zero Impact on Vector Architecture**:
   - Any approved feature must strictly execute within volatile memory (zero disk retention) and must not degrade CAD vector precision or engine security.

