const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("UMSDesktop", {
  retry: () => ipcRenderer.send("ums:retry"),
  close: () => ipcRenderer.send("ums:close"),
  getServer: () => ipcRenderer.invoke("ums:get-server"),
  setServer: (u) => ipcRenderer.invoke("ums:set-server", u),
});
