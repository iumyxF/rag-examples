const docsEl = document.querySelector("#documents"),
  uploadStatus = document.querySelector("#uploadStatus"),
  queryStatus = document.querySelector("#queryStatus");
const stepDefs = [
  ["upload", "1. 上传"], ["docling", "2. Docling"], ["chunking", "3. Chunk"],
  ["embedding", "4. Embedding"], ["elasticsearch", "5. Elasticsearch"],
  ["graph", "6. 图谱"], ["activate", "7. 激活"],
];
const busy = new Set();

async function request(url, options = {}) {
  const response = await fetch(url, options);
  if (!response.ok) {
    let error;
    try { error = await response.json(); } catch { error = { message: await response.text() }; }
    throw new Error(error.message || `${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}

async function loadDocuments() { renderDocuments(await request("/api/documents")); }

function renderDocuments(items) {
  docsEl.replaceChildren();
  items.forEach((item) => docsEl.append(createDocument(item)));
}

function createDocument(detail) {
  const doc = detail.document, version = detail.workingVersion || detail.activeVersion;
  const root = document.createElement("article");
  root.className = "document-card";
  const header = document.createElement("div");
  header.className = "document-header";
  const check = document.createElement("input");
  check.type = "checkbox"; check.value = doc.id; check.disabled = !detail.activeVersion;
  const title = document.createElement("strong"); title.textContent = doc.fileName;
  const actions = document.createElement("span");
  const graph = button("激活图谱", () => showDocumentGraph(doc.id)); graph.disabled = !detail.activeVersion;
  const dropDraft = button("删除工作版本", () => deleteWorking(doc.id), "secondary");
  dropDraft.disabled = !detail.workingVersion || !detail.activeVersion;
  actions.append(graph, dropDraft, button("删除文档", () => deleteDocument(doc.id), "danger"));
  header.append(check, title, badge(detail.workingVersion ? "WORKING" : detail.activeVersion ? "ACTIVE" : "UPLOADED"), actions);

  const pipeline = document.createElement("div"); pipeline.className = "pipeline";
  const states = new Map((version?.steps || []).map((step) => [step.code, step]));
  stepDefs.forEach(([code, label], index) => {
    const state = states.get(code) || { status: "NOT_RUN" };
    const card = document.createElement("section"); card.className = `step step-${state.status.toLowerCase()}`;
    const heading = document.createElement("div"); heading.className = "step-heading";
    const name = document.createElement("strong"); name.textContent = label; heading.append(name, badge(state.status));
    const summary = document.createElement("p");
    summary.className = state.errorMessage ? "step-error" : "step-summary";
    summary.textContent = state.errorMessage || state.summary || "尚未执行";
    const meta = document.createElement("small");
    meta.textContent = state.durationMs == null ? "" : `耗时 ${state.durationMs} ms`;
    const controls = document.createElement("div"); controls.className = "step-actions";
    if (code !== "upload") {
      const run = button(state.status === "SUCCEEDED" ? "重新执行" : "执行", () => runStep(doc.id, code));
      const previous = states.get(stepDefs[index - 1][0]);
      const activeRerun = !detail.workingVersion && detail.activeVersion && code !== "activate";
      run.disabled = busy.has(doc.id) || (!activeRerun && previous?.status !== "SUCCEEDED");
      controls.append(run);
      if (state.status === "SUCCEEDED") controls.append(button("查看", () => showPreview(doc.id, code), "secondary"));
    }
    card.append(heading, summary, meta, controls); pipeline.append(card);
  });
  root.append(header, pipeline);
  return root;
}

function badge(text) {
  const node = document.createElement("span"); node.className = `badge badge-${String(text).toLowerCase()}`;
  node.textContent = text; return node;
}

function button(text, handler, className = "") {
  const node = document.createElement("button"); node.type = "button"; node.textContent = text;
  node.className = className; node.onclick = handler; return node;
}

async function runStep(id, step) {
  busy.add(id); uploadStatus.className = "status"; uploadStatus.textContent = `正在同步执行 ${step}…`;
  await loadDocuments();
  try {
    await request(`/api/documents/${id}/pipeline/${step}`, { method: "POST" });
    uploadStatus.textContent = `${step} 执行成功`;
  } catch (error) {
    uploadStatus.className = "status error"; uploadStatus.textContent = error.message;
  } finally { busy.delete(id); await loadDocuments(); }
}

async function showPreview(id, step) {
  try {
    const value = await request(`/api/documents/${id}/pipeline/${step}/preview?offset=0&limit=20`);
    document.querySelector("#previewTitle").textContent = `${step} 产物预览`;
    document.querySelector("#previewContent").textContent = JSON.stringify(value, null, 2);
    document.querySelector("#previewDialog").showModal();
  } catch (error) { uploadStatus.textContent = error.message; }
}

async function deleteWorking(id) {
  if (!confirm("删除当前工作版本及其中间产物？")) return;
  await request(`/api/documents/${id}/working-version`, { method: "DELETE" }); await loadDocuments();
}

async function deleteDocument(id) {
  if (!confirm("删除文档、全部版本和索引数据？")) return;
  await request(`/api/documents/${id}`, { method: "DELETE" }); await loadDocuments();
}

document.querySelector("#upload").onsubmit = async (event) => {
  event.preventDefault(); uploadStatus.className = "status"; uploadStatus.textContent = "正在保存原文件…";
  try {
    const body = new FormData(); body.append("file", document.querySelector("#file").files[0]);
    const result = await request("/api/documents", { method: "POST", body });
    uploadStatus.textContent = result.existing ? "相同内容已存在" : "上传完成，请手动执行 Docling";
    await loadDocuments();
  } catch (error) { uploadStatus.className = "status error"; uploadStatus.textContent = error.message; }
};

document.querySelector("#ask").onclick = async () => {
  const question = document.querySelector("#question").value.trim(); if (!question) return;
  const documentIds = [...docsEl.querySelectorAll("input:checked")].map((x) => x.value);
  queryStatus.className = "status"; queryStatus.textContent = "查询理解与三路召回执行中…";
  try {
    const result = await request("/api/rag/query", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question, documentIds }) });
    queryStatus.textContent = "完成"; document.querySelector("#answer").textContent = result.answer;
    showCitations(result.citations); document.querySelector("#trace").textContent = JSON.stringify(result.trace, null, 2);
    await renderGraph(result.localGraph);
  } catch (error) { queryStatus.className = "status error"; queryStatus.textContent = error.message; }
};

function showCitations(items) {
  const root = document.querySelector("#citations"); root.replaceChildren();
  for (const citation of items) {
    const details = document.createElement("details"); details.className = "citation";
    const summary = document.createElement("summary");
    const location = citation.pageNumber ? `p.${citation.pageNumber}` : citation.sheetName || citation.sectionPath || "原文";
    summary.textContent = `[${citation.id}] ${citation.fileName} · ${location}`;
    const content = document.createElement("p"); content.textContent = citation.excerpt;
    details.append(summary, content); root.append(details);
  }
}

async function showDocumentGraph(id) {
  const result = await request(`/api/documents/${id}/graph`); await renderGraph(result.mermaid);
}

async function renderGraph(source) {
  document.querySelector("#mermaidSource").textContent = source;
  const root = document.querySelector("#graph"); root.textContent = "";
  try { const { svg } = await window.mermaid.render(`g${Date.now()}`, source); root.innerHTML = svg; }
  catch (error) { root.textContent = `Mermaid 渲染失败：${error.message}`; }
}

loadDocuments().catch((error) => { docsEl.textContent = error.message; });
