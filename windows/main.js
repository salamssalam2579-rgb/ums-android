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
let lastDiscovery = "لم تُجرَ محاولة بعد";
async function discover() {
  const d = cfg.discoveryUrl;
  if (!d) { lastDiscovery = "ملف الاكتشاف غير مضبوط في هذه النسخة"; return false; }
  const ok = (t) => { const u = clean(String(t || "").slice(0, 512)); return u.startsWith("https://") && !/\s/.test(u) ? u : ""; };
  let err = "", u = "";
  try {
    const r = await net.fetch(d + (d.includes("?") ? "&" : "?") + "t=" + Date.now(), { signal: AbortSignal.timeout(10000), headers: { "cache-control": "no-cache" } });
    if (!r.ok) err = "HTTP " + r.status; else { u = ok(await r.text()); if (!u) err = "محتوى غير صالح"; }
  } catch (e) { err = e && e.message ? e.message : String(e); }
  // إن فشلت القراءة أو أعطت العنوان الحالي (قد تكون نسخة قديمة من ذاكرة CDN) نتحقق عبر GitHub API
  if (!u || u === serverUrl()) {
    const m = /gist\.githubusercontent\.com\/([^/]+)\/([0-9a-f]+)\/raw\/([^/?]+)/.exec(d);
    if (m) {
      try {
        const r = await net.fetch("https://api.github.com/gists/" + m[2] + "?t=" + Date.now(), { signal: AbortSignal.timeout(10000), headers: { "cache-control": "no-cache", "user-agent": "ums-windows" } });
        if (r.ok) { const j = await r.json(); const v = ok(j && j.files && j.files[m[3]] && j.files[m[3]].content); if (v) u = v; }
        else if (!u) err += " / API HTTP " + r.status;
      } catch (e) { if (!u) err += " / API: " + (e && e.message ? e.message : e); }
    }
  }
  if (!u) { lastDiscovery = "تعذّرت قراءة ملف الاكتشاف: " + err; return false; }
  if (u === serverUrl()) { lastDiscovery = "العنوان في ملف الاكتشاف هو نفسه الحالي"; return false; }
  saveServer(u); lastDiscovery = "تم العثور على عنوان جديد"; return true;
}

let win = null, errorShown = false;
const sameHost = (u) => { try { return new URL(u).host === new URL(serverUrl()).host; } catch { return false; } };

async function retry() { await discover(); load(); }
function load() { errorShown = false; win.loadURL(serverUrl() + "/"); }

async function onFail() {
  if (errorShown || !win) return;
  errorShown = true;
  if (await discover()) { load(); return; }
  win.loadFile(path.join(__dirname, "error.html"));
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
  // نفق منتهٍ يردّ 530 لا خطأ شبكة: نعامله كانقطاع ونعيد قراءة العنوان
  wc.on("did-navigate", (_e, url, code) => { if (code >= 500 && !url.startsWith("file:")) onFail(); });
  win.on("focus", () => { if (errorShown) retry(); });
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
ipcMain.on("ums:retry", () => { retry(); });
ipcMain.handle("ums:last-discovery", () => lastDiscovery);
ipcMain.on("ums:close", () => app.quit());
ipcMain.handle("ums:get-server", () => serverUrl());
ipcMain.handle("ums:set-server", (_e, u) => { u = clean(u); if (!okUrl(u)) return false; saveServer(u); load(); return true; });

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on("second-instance", () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
  app.whenReady().then(() => {
    Menu.setApplicationMenu(null);
    createWindow();
    setInterval(() => { if (win && errorShown) retry(); }, 60000); // إعادة محاولة صامتة كل دقيقة أثناء ظهور الخطأ
  });
  app.on("window-all-closed", () => app.quit());
}
