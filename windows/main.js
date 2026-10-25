// غلاف UMS لويندوز: يفتح النظام بملء النافذة. الصلاحيات كلها من حساب المستخدم المسجَّل دخوله في النظام نفسه،
// فالتطبيق لا يحمل أي صلاحية أو بيانات خاصة به. يشبه تطبيق أندرويد: عنوان محفوظ + اكتشاف تلقائي + تنزيل الملفات.
const { app, BrowserWindow, Menu, dialog, shell, net, session } = require("electron");
const path = require("path");
const fs = require("fs");

let cfg = { serverUrl: "", discoveryUrl: "", version: "0.0.0" };
try { cfg = { ...cfg, ...JSON.parse(fs.readFileSync(path.join(__dirname, "config.json"), "utf8")) }; } catch { /* يُستعمل الافتراضي */ }

const userFile = () => path.join(app.getPath("userData"), "server.json");
const clean = (u) => String(u || "").trim().replace(/\/+$/, "");
const okUrl = (u) => /^https:\/\/[^\s]+$/.test(u) || /^http:\/\/(localhost|127\.0\.0\.1|192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+)(:\d+)?(\/.*)?$/.test(u);
function serverUrl() {
  try { const u = clean(JSON.parse(fs.readFileSync(userFile(), "utf8")).server); if (okUrl(u)) return u; } catch { /* لا شيء */ }
  return clean(cfg.serverUrl);
}
function saveServer(u) { fs.mkdirSync(app.getPath("userData"), { recursive: true }); fs.writeFileSync(userFile(), JSON.stringify({ server: clean(u) })); }

// يقرأ العنوان الحالي من ملف الاكتشاف ويحفظه إن تغيّر. true إن تغيّر.
async function discover() {
  const d = cfg.discoveryUrl;
  if (!d) return false;
  try {
    const r = await net.fetch(d + (d.includes("?") ? "&" : "?") + "t=" + Date.now(), { signal: AbortSignal.timeout(8000) });
    if (!r.ok) return false;
    const u = clean((await r.text()).slice(0, 512));
    if (!u.startsWith("https://") || /\s/.test(u) || u === serverUrl()) return false;
    saveServer(u);
    return true;
  } catch { return false; }
}

let win = null, errorShown = false;
const sameHost = (u) => { try { return new URL(u).host === new URL(serverUrl()).host; } catch { return false; } };

function load() { errorShown = false; win.loadURL(serverUrl() + "/"); }

async function onFail() {
  if (errorShown || !win) return;
  errorShown = true;
  if (await discover()) { load(); return; }
  win.loadFile(path.join(__dirname, "error.html"), { query: { url: serverUrl() } });
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280, height: 860, minWidth: 900, minHeight: 600, title: "UMS", autoHideMenuBar: true,
    icon: path.join(__dirname, "build", "icon.png"),
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false, sandbox: true, partition: "persist:ums" },
  });
  win.setMenuBarVisibility(false);
  const wc = win.webContents;
  wc.setUserAgent(wc.getUserAgent() + " UMSApp/" + cfg.version + " UMSWindows");
  wc.on("did-fail-load", (_e, code, _d, url, main) => { if (main && code !== -3 && !url.startsWith("file:")) onFail(); });
  wc.on("did-finish-load", () => { errorShown = false; });
  // الروابط الخارجية في المتصفح الافتراضي، وروابط النظام داخل النافذة
  wc.setWindowOpenHandler(({ url }) => { if (sameHost(url)) return { action: "allow" }; if (/^https?:/.test(url)) shell.openExternal(url); return { action: "deny" }; });
  wc.on("will-navigate", (e, url) => { if (url.startsWith("file:") || sameHost(url)) return; e.preventDefault(); if (/^https?:/.test(url)) shell.openExternal(url); });
  // تنزيلات الواجهة (PDF/Excel/نسخ احتياطي): تُحفظ في مجلد التنزيلات وتُفتح
  session.fromPartition("persist:ums").on("will-download", (_e, item) => {
    const dest = path.join(app.getPath("downloads"), item.getFilename().replace(/[\\/:*?"<>|]/g, "_"));
    item.setSavePath(dest);
    item.once("done", (_ev, state) => { if (state === "completed") shell.showItemInFolder(dest); });
  });
  load();
  discover().then((changed) => { if (changed) load(); });
}

// أوامر صفحة الخطأ
const { ipcMain } = require("electron");
ipcMain.on("ums:retry", () => load());
ipcMain.on("ums:close", () => app.quit());
ipcMain.handle("ums:get-server", () => serverUrl());
ipcMain.handle("ums:set-server", (_e, u) => { u = clean(u); if (!okUrl(u)) return false; saveServer(u); load(); return true; });

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on("second-instance", () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
  app.whenReady().then(() => {
    Menu.setApplicationMenu(null);
    createWindow();
    setInterval(() => { if (win && errorShown) load(); }, 60000); // إعادة محاولة صامتة كل دقيقة أثناء ظهور الخطأ
  });
  app.on("window-all-closed", () => app.quit());
}
