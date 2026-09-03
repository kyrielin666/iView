const titles = {
  overview: "运行总览",
  devices: "设备中心",
  data: "数据与查询",
  dashboard: "可视化看板",
  migration: "融合进度",
};

function showView(name) {
  document.querySelectorAll(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.view === name));
  document.querySelectorAll(".view").forEach((panel) => panel.classList.toggle("active", panel.dataset.panel === name));
  document.querySelector("#page-title").textContent = titles[name];
  if (name === "migration") loadParity();
  if (name === "devices") loadCatalog();
}

document.querySelectorAll(".nav-item").forEach((item) => item.addEventListener("click", () => showView(item.dataset.view)));
document.querySelectorAll("[data-jump]").forEach((item) => item.addEventListener("click", () => showView(item.dataset.jump)));

function updateClock() {
  const now = new Date();
  document.querySelector("#clock").textContent = new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
  }).format(now).replaceAll("/", ".");
}
updateClock();
setInterval(updateClock, 1000);

async function loadBackend() {
  const label = document.querySelector("#backend-label");
  const state = document.querySelector("#backend-state");
  const value = document.querySelector("#backend-value");
  const dot = document.querySelector(".pulse-dot");
  try {
    const response = await fetch("/actuator/health", { cache: "no-store" });
    const health = await response.json();
    if (!response.ok || health.status !== "UP") throw new Error();
    label.textContent = "后端服务正常";
    state.textContent = "在线";
    value.textContent = "UP";
    value.style.color = "var(--green)";
    dot.classList.add("up");
  } catch {
    label.textContent = "后端服务未连接";
    state.textContent = "离线";
    value.textContent = "DOWN";
    value.style.color = "var(--red)";
    dot.classList.remove("up");
  }
}

async function loadParity() {
  const list = document.querySelector("#parity-list");
  list.innerHTML = '<p class="loading">正在读取功能台账…</p>';
  try {
    const response = await fetch("/meta/parity", { cache: "no-store" });
    const { counts, rows } = await response.json();
    document.querySelector("#parity-total").textContent = counts.total;
    document.querySelector("#parity-progress").textContent = counts.IN_PROGRESS;
    document.querySelector("#parity-verified").textContent = counts.VERIFIED;
    document.querySelector("#parity-pending").textContent = counts.NOT_STARTED;
    document.querySelector("#progress-bar").style.width = `${counts.total ? (counts.VERIFIED / counts.total) * 100 : 0}%`;
    list.innerHTML = rows.map((row) => `
      <div class="parity-row">
        <div><strong>${escapeHtml(row.capability)}</strong><small>${escapeHtml(row.reference.replaceAll("`", ""))}</small></div>
        <span class="parity-status ${row.status.toLowerCase().replaceAll("_", "-")}">${statusText(row.status)}</span>
      </div>
    `).join("");
  } catch {
    list.innerHTML = '<p class="loading">功能台账读取失败，请确认前端服务从 iView/frontend 启动。</p>';
  }
}

function statusText(status) {
  return { NOT_STARTED: "未开始", IN_PROGRESS: "进行中", VERIFIED: "已验收" }[status] || status;
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>'"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[char]);
}

let catalog = { groups: [], templates: [], devices: [] };

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: options.body ? { "Content-Type": "application/json", ...(options.headers || {}) } : options.headers,
  });
  const payload = await response.json().catch(() => ({ msg: "服务返回格式错误" }));
  if (!response.ok || payload.code !== "200") throw new Error(payload.msg || `请求失败 (${response.status})`);
  return payload.data;
}

async function loadCatalog() {
  const state = document.querySelector("#catalog-state");
  if (!state) return;
  state.classList.remove("error");
  state.textContent = "正在同步设备目录…";
  const keyword = document.querySelector("#device-search").value.trim();
  try {
    const [groups, templatesPage, devicesPage] = await Promise.all([
      api("/api/v1/device-groups"),
      api("/api/v1/templates?page=1&page_size=100"),
      api(`/api/v1/devices?page=1&page_size=100&keyword=${encodeURIComponent(keyword)}`),
    ]);
    catalog = { groups, templates: templatesPage.list, devices: devicesPage.list };
    renderCatalog();
    state.textContent = `已同步 · ${new Date().toLocaleTimeString("zh-CN", { hour12: false })}`;
  } catch (error) {
    state.classList.add("error");
    state.textContent = error.message;
    document.querySelector("#device-list").innerHTML = '<tr><td colspan="5" class="empty-cell">设备服务不可用，请先启动 iView 后端。</td></tr>';
  }
}

function renderCatalog() {
  const groupNames = new Map(catalog.groups.map((group) => [group.id, group.group_name]));
  document.querySelector("#device-count").textContent = `${catalog.devices.length} 台`;
  document.querySelector("#template-count").textContent = `${catalog.templates.length} 个`;
  document.querySelector("#device-list").innerHTML = catalog.devices.length ? catalog.devices.map((device) => `
    <tr>
      <td><strong>${escapeHtml(device.device_name)}</strong><small>${escapeHtml(device.device_sn)}</small></td>
      <td>${escapeHtml(device.template?.template_name || "-")}<small>${escapeHtml(device.template?.protocol_type || "-")}</small></td>
      <td>${escapeHtml(groupNames.get(device.group_id) || "未分组")}</td>
      <td><span class="live-state ${device.enabled ? "running" : "idle"}">${device.enabled ? "已启用" : "已停用"}</span></td>
      <td><div class="row-actions">${device.template?.protocol_type === "modbus_tcp" ? `<button class="mini-button" data-device-action="diagnose" data-id="${device.id}" data-name="${escapeHtml(device.device_name)}">诊断</button>` : ""}<button class="mini-button" data-device-action="toggle" data-id="${device.id}" data-enabled="${device.enabled}">${device.enabled ? "停用" : "启用"}</button><button class="mini-button danger" data-device-action="delete" data-id="${device.id}">删除</button></div></td>
    </tr>
  `).join("") : '<tr><td colspan="5" class="empty-cell">暂无设备。请先新建设备组和模板，再添加设备。</td></tr>';

  document.querySelector("#template-list").innerHTML = catalog.templates.length ? catalog.templates.map((template) => `
    <article class="template-card">
      <header><strong>${escapeHtml(template.template_name)}</strong><code>${escapeHtml(template.protocol_type)}</code></header>
      <p>${escapeHtml(template.description || "暂无说明")}</p>
      <footer><span>${template.points?.length || 0} 个采集点</span><span>${template.collect_interval} ms</span></footer>
    </article>
  `).join("") : '<p class="loading">暂无设备模板</p>';

  const templateSelect = document.querySelector("#device-template");
  templateSelect.innerHTML = catalog.templates.length
    ? catalog.templates.map((template) => `<option value="${template.id}">${escapeHtml(template.template_name)} · ${escapeHtml(template.protocol_type)}</option>`).join("")
    : '<option value="">请先创建设备模板</option>';
  document.querySelector("#device-group").innerHTML = '<option value="">不分组</option>' + catalog.groups.map((group) => `<option value="${group.id}">${escapeHtml(group.group_name)}</option>`).join("");
}

document.querySelectorAll("[data-dialog]").forEach((button) => button.addEventListener("click", async () => {
  await loadCatalog();
  document.querySelector(`#${button.dataset.dialog}`).showModal();
}));

document.querySelectorAll("[data-close-dialog]").forEach((button) => button.addEventListener("click", () => {
  const dialog = button.closest("dialog");
  const error = dialog.querySelector(".form-error");
  if (error) error.textContent = "";
  dialog.close();
}));

document.querySelectorAll(".form-dialog form").forEach((form) => form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(form));
  const error = form.querySelector(".form-error");
  error.textContent = "";
  try {
    if (form.dataset.form === "group") {
      await api("/api/v1/device-groups", { method: "POST", body: JSON.stringify(data) });
    } else if (form.dataset.form === "template") {
      await api("/api/v1/templates", { method: "POST", body: JSON.stringify({
        ...data, collect_interval: Number(data.collect_interval), timeout: Number(data.timeout), protocol_cfg: {},
      }) });
    } else if (form.dataset.form === "device") {
      if (!data.template_id) throw new Error("请先选择设备模板");
      await api("/api/v1/devices", { method: "POST", body: JSON.stringify({
        device_sn: data.device_sn,
        device_name: data.device_name,
        template_id: Number(data.template_id),
        group_id: data.group_id ? Number(data.group_id) : null,
        config_json: { host: data.host, port: Number(data.port) },
        enabled: 1,
        description: data.description,
      }) });
    }
    form.reset();
    form.closest("dialog").close();
    await loadCatalog();
  } catch (cause) {
    error.textContent = cause.message;
  }
}));

document.querySelector("#device-list").addEventListener("click", async (event) => {
  const button = event.target.closest("[data-device-action]");
  if (!button) return;
  try {
    if (button.dataset.deviceAction === "diagnose") {
      await diagnoseDevice(button.dataset.id, button.dataset.name);
      return;
    } else if (button.dataset.deviceAction === "toggle") {
      const action = button.dataset.enabled === "1" ? "disable" : "enable";
      await api(`/api/v1/devices/${button.dataset.id}/${action}`, { method: "PUT" });
    } else if (button.dataset.deviceAction === "delete") {
      if (!confirm("确认删除这台设备？")) return;
      await api(`/api/v1/devices/${button.dataset.id}`, { method: "DELETE" });
    }
    await loadCatalog();
  } catch (error) {
    document.querySelector("#catalog-state").textContent = error.message;
    document.querySelector("#catalog-state").classList.add("error");
  }
});

async function diagnoseDevice(id, name) {
  const dialog = document.querySelector("#diagnostic-dialog");
  document.querySelector("#diagnostic-title").textContent = `设备诊断 · ${name}`;
  document.querySelector("#diagnostic-summary").innerHTML = '<p class="loading">正在检查设备配置和网络连接…</p>';
  document.querySelector("#diagnostic-steps").innerHTML = "";
  dialog.showModal();
  try {
    const result = await api(`/api/v1/devices/${id}/diagnose`, { method: "POST" });
    document.querySelector("#diagnostic-summary").innerHTML = `
      <span class="live-state ${result.overall_status === "success" ? "running" : "fault"}">${result.overall_status === "success" ? "通过" : "未通过"}</span>
      <div><strong>${escapeHtml(result.summary)}</strong><small>${new Date(result.diagnosed_at).toLocaleString("zh-CN")}</small></div>
    `;
    document.querySelector("#diagnostic-steps").innerHTML = result.steps.map((step) => `
      <article class="diagnostic-step ${step.status}">
        <i></i><div><strong>${escapeHtml(step.title)}</strong><p>${escapeHtml(step.message || "已完成")}</p></div><span>${step.duration_ms} ms</span>
      </article>
    `).join("");
  } catch (error) {
    document.querySelector("#diagnostic-summary").innerHTML = `<p class="form-error">${escapeHtml(error.message)}</p>`;
  }
}

let searchTimer;
document.querySelector("#device-search").addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(loadCatalog, 250);
});
document.querySelector("#device-refresh").addEventListener("click", loadCatalog);

document.querySelector("#refresh-button").addEventListener("click", () => Promise.all([loadBackend(), loadParity()]));
document.querySelector("#parity-refresh").addEventListener("click", loadParity);

loadBackend();
loadParity();
