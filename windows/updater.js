// تحديث غلاف ويندوز: يسأل خادم النظام نفسه (لا يتصل بـGitHub) عن إصدار أحدث، وينزّل ملف التثبيت ويتحقق منه.
const fs = require("fs");
const path = require("path");
const { Readable } = require("stream");
const { pipeline } = require("stream/promises");

const num = (v) => String(v || "0").split(".").map((x) => parseInt(x, 10) || 0);
function newer(a, b) { const x = num(a), y = num(b); for (let i = 0; i < 3; i++) { if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0); } return false; }

// يعيد وصف الإصدار الأحدث أو null. fetchFn لتبديلها في الاختبار.
async function check(base, current, fetchFn) {
  try {
    const r = await fetchFn(base + "/api/app/windows?version=" + encodeURIComponent(current), { signal: AbortSignal.timeout(15000), headers: { "cache-control": "no-cache" } });
    if (!r.ok) return null;
    const j = await r.json();
    if (!j || !j.available || !newer(j.version, current)) return null;
    if (typeof j.url !== "string" || !j.url.startsWith("/api/app/windows/")) return null;
    return { version: String(j.version), size: Number(j.size) || 0, url: j.url, notes: Array.isArray(j.notes) ? j.notes : [] };
  } catch { return null; }
}

// ينزّل ملف التثبيت إلى مجلد مؤقت ويتحقق من الحجم وأنه ملف تنفيذي. يعيد المسار أو يرمي خطأً.
async function download(base, info, dir, fetchFn) {
  fs.mkdirSync(dir, { recursive: true });
  const dest = path.join(dir, "UMS-Setup-" + info.version + ".exe"), tmp = dest + ".part";
  const r = await fetchFn(base + info.url, { signal: AbortSignal.timeout(15 * 60 * 1000) });
  if (!r.ok || !r.body) throw new Error("HTTP " + r.status);
  await pipeline(Readable.fromWeb(r.body), fs.createWriteStream(tmp));
  const st = fs.statSync(tmp);
  const fd = fs.openSync(tmp, "r"), b = Buffer.alloc(2); fs.readSync(fd, b, 0, 2, 0); fs.closeSync(fd);
  if (b.toString("latin1") !== "MZ" || st.size < 5_000_000 || (info.size && st.size !== info.size)) { fs.rmSync(tmp, { force: true }); throw new Error("الملف المنزَّل غير صالح"); }
  fs.renameSync(tmp, dest);
  return dest;
}

module.exports = { newer, check, download };
