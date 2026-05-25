"use client";

import { useEffect, useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { PageShell } from "@/components/page-shell";
import { useApiResource, POLL } from "@/hooks/use-api-resource";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  SelectGroup,
  SelectLabel,
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { apiFetch, apiUpload } from "@/lib/api";
import { toast } from "sonner";
import {
  Plus,
  Trash2,
  BoxIcon,
  MoreVertical,
  Play,
  Square,
  RotateCw,
  Package,
  Upload,
  Loader2,
  Pencil,
  TerminalIcon,
} from "@/lib/icons";
import { Field, FieldLabel, FieldDescription } from "@/components/ui/field";
import { ServiceConsoleSheet } from "@/components/service-console-sheet";

interface DedicatedService {
  name: string;
  directory: string;
  port: number;
  software: string;
  version: string;
  memory: string;
  proxyEnabled: boolean;
  restartOnCrash: boolean;
  maxRestarts: number;
  jvmArgs: string[];
  jvmOptimize: boolean;
  state: string | null;
  pid: number | null;
  playerCount: number | null;
  uptime: string | null;
}

interface DedicatedListResponse {
  services: DedicatedService[];
  total: number;
}

interface SoftwareInfo {
  name: string;
  needsModloaderVersion: boolean;
  needsCustomJar: boolean;
  isProxy: boolean;
}

interface VersionListResponse {
  software: string;
  stable: string[];
  snapshots: string[];
  latest: string | null;
}

interface ModpackImportResponse {
  success: boolean;
  message: string;
  groupName: string;
  filesDownloaded: number;
  filesFailed: number;
}

interface ModpackInfo {
  name: string;
  version: string;
  mcVersion: string;
  modloader: string;
  modloaderVersion: string;
  totalFiles: number;
  serverFiles: number;
  source: string;
}

function stateBadgeVariant(state: string | null): "default" | "secondary" | "destructive" | "outline" {
  if (!state) return "outline";
  if (state === "READY") return "default";
  if (state === "CRASHED" || state === "STOPPED") return "destructive";
  if (state === "STARTING" || state === "PREPARING" || state === "STOPPING") return "secondary";
  return "outline";
}

export default function DedicatedPage() {
  const {
    data: dedResp,
    loading,
    error,
    refetch: load,
  } = useApiResource<DedicatedListResponse>("/api/dedicated", {
    poll: POLL.normal,
  });
  const services = dedResp?.services ?? [];
  const [createOpen, setCreateOpen] = useState(false);

  // Create form state
  const [softwareList, setSoftwareList] = useState<SoftwareInfo[]>([]);
  const [newName, setNewName] = useState("");
  const [newPort, setNewPort] = useState(25570);
  const [newSoftware, setNewSoftware] = useState("PAPER");
  const [newVersion, setNewVersion] = useState("");
  const [newMemory, setNewMemory] = useState("2G");
  const [newProxyEnabled, setNewProxyEnabled] = useState(true);
  const [newRestartOnCrash, setNewRestartOnCrash] = useState(true);
  const [versions, setVersions] = useState<VersionListResponse | null>(null);
  const [modloaderVersions, setModloaderVersions] = useState<VersionListResponse | null>(null);
  const [newModloaderVersion, setNewModloaderVersion] = useState("");
  const [loadingVersions, setLoadingVersions] = useState(false);
  const [loadingModloader, setLoadingModloader] = useState(false);
  const [creating, setCreating] = useState(false);

  const selectedSoftware = softwareList.find((s) => s.name === newSoftware);

  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [consoleService, setConsoleService] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [actioning, setActioning] = useState<string | null>(null);

  // Edit state
  const [editTarget, setEditTarget] = useState<DedicatedService | null>(null);
  const [editPort, setEditPort] = useState(25570);
  const [editMemory, setEditMemory] = useState("2G");
  const [editProxyEnabled, setEditProxyEnabled] = useState(true);
  const [editRestartOnCrash, setEditRestartOnCrash] = useState(true);
  const [saving, setSaving] = useState(false);

  function openEdit(service: DedicatedService) {
    setEditTarget(service);
    setEditPort(service.port);
    setEditMemory(service.memory);
    setEditProxyEnabled(service.proxyEnabled);
    setEditRestartOnCrash(service.restartOnCrash);
  }

  async function saveEdit() {
    if (!editTarget) return;
    setSaving(true);
    try {
      // Backend PUT accepts the full CreateDedicatedRequest shape; send existing
      // values for fields we don't expose in the edit dialog.
      const result = await apiFetch<{ success: boolean; message: string }>(
        `/api/dedicated/${editTarget.name}`,
        {
          method: "PUT",
          body: JSON.stringify({
            name: editTarget.name,
            port: editPort,
            software: editTarget.software,
            version: editTarget.version,
            jarName: "",
            readyPattern: "",
            javaPath: "",
            proxyEnabled: editProxyEnabled,
            memory: editMemory,
            restartOnCrash: editRestartOnCrash,
            maxRestarts: editTarget.maxRestarts,
            jvmArgs: editTarget.jvmArgs,
            jvmOptimize: editTarget.jvmOptimize,
          }),
        }
      );
      toast.success(result.message);
      setEditTarget(null);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to update");
    } finally {
      setSaving(false);
    }
  }

  // Fetch software list when dialog opens
  useEffect(() => {
    if (!createOpen) return;
    apiFetch<{ software: SoftwareInfo[] }>("/api/software")
      .then((data) => setSoftwareList(data.software))
      .catch(() => {});
  }, [createOpen]);

  // Fetch versions when software changes
  useEffect(() => {
    if (!createOpen || newSoftware === "CUSTOM") {
      setVersions(null);
      return;
    }
    setLoadingVersions(true);
    setNewVersion("");
    apiFetch<VersionListResponse>(`/api/software/${newSoftware}/versions`)
      .then((data) => {
        setVersions(data);
        if (data.latest) setNewVersion(data.latest);
      })
      .catch(() => {})
      .finally(() => setLoadingVersions(false));
  }, [newSoftware, createOpen]);

  // Fetch modloader versions when MC version changes (for Forge/NeoForge/Fabric)
  useEffect(() => {
    if (!selectedSoftware?.needsModloaderVersion || !newVersion) {
      setModloaderVersions(null);
      return;
    }
    setLoadingModloader(true);
    setNewModloaderVersion("");
    apiFetch<VersionListResponse>(
      `/api/software/${newSoftware}/modloader-versions?mcVersion=${newVersion}`
    )
      .then((data) => {
        setModloaderVersions(data);
        if (data.latest) setNewModloaderVersion(data.latest);
      })
      .catch(() => {})
      .finally(() => setLoadingModloader(false));
  }, [newSoftware, newVersion, selectedSoftware?.needsModloaderVersion]);

  function resetCreateForm() {
    setNewName("");
    setNewPort(25570);
    setNewSoftware("PAPER");
    setNewVersion("");
    setNewMemory("2G");
    setNewProxyEnabled(true);
    setNewRestartOnCrash(true);
    setVersions(null);
    setModloaderVersions(null);
    setNewModloaderVersion("");
  }

  async function createService() {
    if (!newName.trim()) return;
    setCreating(true);
    try {
      await apiFetch("/api/dedicated", {
        method: "POST",
        body: JSON.stringify({
          name: newName.trim(),
          port: newPort,
          software: newSoftware,
          version: newVersion,
          memory: newMemory,
          proxyEnabled: newProxyEnabled,
          restartOnCrash: newRestartOnCrash,
          maxRestarts: 5,
          jvmArgs: [],
          jvmOptimize: true,
        }),
      });
      toast.success(`Dedicated service '${newName}' created`);
      setCreateOpen(false);
      resetCreateForm();
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to create dedicated service");
    } finally {
      setCreating(false);
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await apiFetch(`/api/dedicated/${deleteTarget}`, { method: "DELETE" });
      toast.success(`Dedicated service '${deleteTarget}' deleted`);
      setDeleteTarget(null);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to delete");
    } finally {
      setDeleting(false);
    }
  }

  async function doAction(name: string, action: "start" | "stop" | "restart") {
    setActioning(name);
    try {
      await apiFetch(`/api/dedicated/${name}/${action}`, { method: "POST" });
      toast.success(`Dedicated service '${name}' ${action}ed`);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : `Failed to ${action}`);
    } finally {
      setActioning(null);
    }
  }

  // Modpack import state
  const [importOpen, setImportOpen] = useState(false);
  const [importMode, setImportMode] = useState<"source" | "upload">("source");
  const [importSource, setImportSource] = useState("");
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importServiceName, setImportServiceName] = useState("");
  const [importPort, setImportPort] = useState(25580);
  const [importMemory, setImportMemory] = useState("4G");
  const [importProxyEnabled, setImportProxyEnabled] = useState(true);
  const [resolving, setResolving] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importProgress, setImportProgress] = useState("");
  const [importInfo, setImportInfo] = useState<ModpackInfo | null>(null);

  function resetImportState() {
    setImportSource("");
    setImportFile(null);
    setImportServiceName("");
    setImportPort(25580);
    setImportMemory("4G");
    setImportProxyEnabled(true);
    setImportProgress("");
    setImportMode("source");
    setImportInfo(null);
  }

  async function resolveModpack() {
    if (!importSource.trim()) return;
    setResolving(true);
    setImportInfo(null);
    try {
      const info = await apiFetch<ModpackInfo>("/api/modpacks/resolve", {
        method: "POST",
        body: JSON.stringify({ source: importSource.trim() }),
      });
      setImportInfo(info);
      if (!importServiceName) {
        setImportServiceName(info.name.replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 20) || "modpack");
      }
      if (info.source === "SERVER_PACK") setImportMemory("4G");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not resolve modpack");
    } finally {
      setResolving(false);
    }
  }

  function handleFileSelect(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setImportFile(file);
    const name = file.name
      .replace(/\.zip$|\.mrpack$/i, "")
      .replace(/[-_]?[Ss]erver[Ff]iles[-_]?/g, "")
      .replace(/[-_]?[Ss]erver[-_]?[Pp]ack[-_]?/g, "")
      .replace(/[^a-zA-Z0-9_-]/g, "")
      .slice(0, 20) || "modpack";
    if (!importServiceName) setImportServiceName(name);
  }

  /**
   * Poll the dedicated list until `name` shows up, or give up after `timeoutMs`.
   * Used as a recovery path when an upload connection drops *after* all bytes
   * were streamed but before the server flushed its response — the import is
   * still running server-side and the service eventually materialises.
   */
  async function waitForDedicatedAppears(
    name: string,
    timeoutMs = 120_000,
    intervalMs = 4_000,
  ): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      try {
        const res = await apiFetch<{ services: Array<{ name: string }> }>(
          "/api/dedicated",
          { silent: true },
        );
        if (res.services.some((s) => s.name === name)) return true;
      } catch {
        // Ignore transient errors; keep polling.
      }
      await new Promise((r) => setTimeout(r, intervalMs));
    }
    return false;
  }

  async function importModpack() {
    if (!importServiceName.trim()) return;
    setImporting(true);
    setImportProgress("Importing...");
    const serviceName = importServiceName.trim();
    try {
      if (importMode === "upload" && importFile) {
        setImportProgress(`Uploading ${importFile.name}...`);
        const params = new URLSearchParams({
          name: serviceName,
          port: String(importPort),
          memory: importMemory,
          proxyEnabled: String(importProxyEnabled),
          fileName: importFile.name,
        });
        let result: ModpackImportResponse;
        try {
          result = await apiUpload<ModpackImportResponse>(
            `/api/dedicated/modpack/upload?${params.toString()}`,
            importFile,
            (uploaded, total) => {
              const pct = Math.round((uploaded / total) * 100);
              setImportProgress(`Uploading ${importFile.name}... ${pct}%`);
            }
          );
        } catch (err) {
          // The controller often takes 30–60 s to extract a 1+ GB server pack
          // and run the modloader installer after receiving the bytes. Idle
          // timeouts in proxies between the browser and the controller can cut
          // the connection before the final HTTP response arrives, even though
          // the import succeeds server-side. Detect that case and wait for the
          // service to materialise instead of surfacing a spurious error.
          const msg = err instanceof Error ? err.message : String(err);
          const looksLikeNetworkDrop =
            err instanceof TypeError ||
            /Failed to fetch|NetworkError|disconnected|aborted|ERR_/i.test(msg);
          if (!looksLikeNetworkDrop) throw err;

          setImportProgress("Connection dropped — waiting for server to finish import…");
          const appeared = await waitForDedicatedAppears(serviceName);
          if (!appeared) {
            throw new Error(
              "Upload connection dropped and the service did not appear within 2 min. Check the controller logs.",
            );
          }
          result = {
            success: true,
            message: `Dedicated service '${serviceName}' imported (response timed out, but service is registered).`,
            groupName: serviceName,
            filesDownloaded: 0,
            filesFailed: 0,
          };
        }
        if (result.filesFailed > 0) {
          toast.warning(`Imported with ${result.filesFailed} failed downloads`);
        } else {
          toast.success(result.message);
        }
      } else {
        setImportProgress("Downloading and installing...");
        const result = await apiFetch<ModpackImportResponse>("/api/dedicated/modpack/import", {
          method: "POST",
          body: JSON.stringify({
            source: importSource.trim(),
            name: serviceName,
            port: importPort,
            memory: importMemory,
            proxyEnabled: importProxyEnabled,
          }),
        });
        if (result.filesFailed > 0) {
          toast.warning(`Imported with ${result.filesFailed} failed downloads`);
        } else {
          toast.success(result.message);
        }
      }
      setImportOpen(false);
      resetImportState();
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Import failed");
    } finally {
      setImporting(false);
      setImportProgress("");
    }
  }

  const canImport = importServiceName.trim() && (
    (importMode === "source" && importSource.trim()) ||
    (importMode === "upload" && importFile)
  );

  const headerActions = (
    <>
      <Dialog
        open={importOpen}
        onOpenChange={(open) => {
          setImportOpen(open);
          if (!open) resetImportState();
        }}
      >
              <DialogTrigger
                render={
                  <Button variant="outline">
                    <Package className="mr-1 size-4" /> Import Modpack
                  </Button>
                }
              />
              <DialogContent className="max-w-lg max-h-[85vh] overflow-y-auto">
                <DialogHeader>
                  <DialogTitle>Import Modpack as Dedicated Service</DialogTitle>
                  <DialogDescription>
                    Import from Modrinth, CurseForge, or upload a server pack ZIP.
                  </DialogDescription>
                </DialogHeader>
                <div className="space-y-4 py-2">
                  {/* Mode toggle */}
                  <div className="flex gap-2">
                    <Button
                      variant={importMode === "source" ? "default" : "outline"}
                      size="sm"
                      className="flex-1"
                      onClick={() => setImportMode("source")}
                    >
                      <Package className="mr-1 size-3.5" /> Modrinth / CurseForge
                    </Button>
                    <Button
                      variant={importMode === "upload" ? "default" : "outline"}
                      size="sm"
                      className="flex-1"
                      onClick={() => setImportMode("upload")}
                    >
                      <Upload className="mr-1 size-3.5" /> Upload Server Pack
                    </Button>
                  </div>

                  {importMode === "source" ? (
                    <Field>
                      <FieldLabel>Modpack Source</FieldLabel>
                      <div className="flex items-center gap-2">
                        <Input
                          value={importSource}
                          onChange={(e) => setImportSource(e.target.value)}
                          placeholder="Slug, URL, or curseforge:slug"
                          onKeyDown={(e) => e.key === "Enter" && resolveModpack()}
                        />
                        <Button variant="outline" onClick={resolveModpack} disabled={resolving || !importSource.trim()}>
                          {resolving ? <Loader2 className="size-4 animate-spin" /> : "Resolve"}
                        </Button>
                      </div>
                      <FieldDescription>
                        e.g. &quot;adrenaserver&quot;, a Modrinth/CurseForge URL, or &quot;curseforge:all-the-mods-10&quot;
                      </FieldDescription>
                    </Field>
                  ) : (
                    <Field>
                      <FieldLabel>Server Pack File</FieldLabel>
                      <div
                        className="relative flex flex-col items-center justify-center rounded-md border-2 border-dashed p-6 transition-colors hover:border-primary/50 cursor-pointer"
                        onClick={() => document.getElementById("dedicated-modpack-file-input")?.click()}
                        onDragOver={(e) => { e.preventDefault(); e.stopPropagation(); }}
                        onDrop={(e) => {
                          e.preventDefault();
                          e.stopPropagation();
                          const file = e.dataTransfer.files[0];
                          if (file && (file.name.endsWith(".zip") || file.name.endsWith(".mrpack"))) {
                            setImportFile(file);
                            const name = file.name
                              .replace(/\.zip$|\.mrpack$/i, "")
                              .replace(/[-_]?[Ss]erver[Ff]iles[-_]?/g, "")
                              .replace(/[-_]?[Ss]erver[-_]?[Pp]ack[-_]?/g, "")
                              .replace(/[^a-zA-Z0-9_-]/g, "")
                              .slice(0, 20) || "modpack";
                            if (!importServiceName) setImportServiceName(name);
                          } else {
                            toast.error("Please drop a .zip or .mrpack file");
                          }
                        }}
                      >
                        <input
                          id="dedicated-modpack-file-input"
                          type="file"
                          accept=".zip,.mrpack"
                          className="hidden"
                          onChange={handleFileSelect}
                        />
                        {importFile ? (
                          <>
                            <Package className="size-8 text-primary mb-2" />
                            <p className="text-sm font-medium">{importFile.name}</p>
                            <p className="text-xs text-muted-foreground">
                              {(importFile.size / 1024 / 1024).toFixed(1)} MB
                            </p>
                          </>
                        ) : (
                          <>
                            <Upload className="size-8 text-muted-foreground/50 mb-2" />
                            <p className="text-sm text-muted-foreground">
                              Drop a server pack ZIP or .mrpack here
                            </p>
                            <p className="text-xs text-muted-foreground/70 mt-1">
                              or click to browse
                            </p>
                          </>
                        )}
                      </div>
                      <FieldDescription>
                        Upload a CurseForge server pack ZIP (e.g. ServerFiles-6.6.zip) or .mrpack file
                      </FieldDescription>
                    </Field>
                  )}

                  {importInfo && (
                    <div className="rounded-md border p-3 space-y-1 text-sm">
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{importInfo.name}</span>
                        {importInfo.version && <Badge variant="outline">v{importInfo.version}</Badge>}
                        <Badge variant="secondary">
                          {importInfo.source === "SERVER_PACK"
                            ? "Server Pack"
                            : importInfo.source === "CURSEFORGE_API"
                            ? "CurseForge"
                            : "Modrinth"}
                        </Badge>
                      </div>
                      <div className="text-muted-foreground">
                        {importInfo.modloader} {importInfo.modloaderVersion} / MC {importInfo.mcVersion}
                      </div>
                      <div className="text-muted-foreground">
                        {importInfo.serverFiles} server mods
                        {importInfo.totalFiles !== importInfo.serverFiles
                          ? ` (${importInfo.totalFiles} total)`
                          : ""}
                      </div>
                    </div>
                  )}

                  <Field>
                    <FieldLabel>Service Name</FieldLabel>
                    <Input
                      value={importServiceName}
                      onChange={(e) => setImportServiceName(e.target.value)}
                      placeholder="e.g. ATM10"
                    />
                    <FieldDescription>
                      Unique identifier. The service directory will be auto-created at{" "}
                      <code className="font-mono text-xs">dedicated/{importServiceName || "<name>"}/</code>.
                    </FieldDescription>
                  </Field>

                  <div className="grid grid-cols-2 gap-3">
                    <Field>
                      <FieldLabel>Port</FieldLabel>
                      <Input
                        type="number"
                        min={1}
                        max={65535}
                        value={importPort}
                        onChange={(e) => setImportPort(Number(e.target.value))}
                      />
                    </Field>
                    <Field>
                      <FieldLabel>Memory</FieldLabel>
                      <Input
                        value={importMemory}
                        onChange={(e) => setImportMemory(e.target.value)}
                        placeholder="4G"
                      />
                    </Field>
                  </div>

                  <Field>
                    <div className="flex items-center justify-between">
                      <div>
                        <FieldLabel>Proxy Enabled</FieldLabel>
                        <FieldDescription>
                          Register with Velocity proxy and patch forwarding config.
                        </FieldDescription>
                      </div>
                      <Switch
                        checked={importProxyEnabled}
                        onCheckedChange={setImportProxyEnabled}
                      />
                    </div>
                  </Field>
                </div>
                <DialogFooter>
                  {importProgress && (
                    <div className="flex items-center gap-2 mr-auto text-sm text-muted-foreground">
                      <Loader2 className="size-4 animate-spin" />
                      {importProgress}
                    </div>
                  )}
                  <Button onClick={importModpack} disabled={importing || !canImport}>
                    {importing ? "Importing..." : importMode === "upload" ? "Upload & Import" : "Import"}
                  </Button>
                </DialogFooter>
              </DialogContent>
            </Dialog>

            {/* Create Dedicated Dialog */}
            <Dialog
              open={createOpen}
              onOpenChange={(open) => {
                setCreateOpen(open);
                if (!open) resetCreateForm();
              }}
            >
              <DialogTrigger
                render={
                  <Button>
                    <Plus className="mr-1 size-4" /> New Dedicated
                  </Button>
                }
              />
              <DialogContent className="max-w-lg max-h-[85vh] overflow-y-auto">
                <DialogHeader>
                  <DialogTitle>Create Dedicated Service</DialogTitle>
                  <DialogDescription>
                    A dedicated service points to an existing server directory with a fixed
                    name and port. No templates, no scaling.
                  </DialogDescription>
                </DialogHeader>
                <div className="space-y-4 py-2">
                  <Field>
                    <FieldLabel>Name</FieldLabel>
                    <Input
                      value={newName}
                      onChange={(e) => setNewName(e.target.value)}
                      placeholder="e.g. sandbox"
                    />
                    <FieldDescription>
                      Unique identifier. The service directory will be auto-created at{" "}
                      <code className="font-mono text-xs">dedicated/{newName || "<name>"}/</code>.
                    </FieldDescription>
                  </Field>

                  <div className="grid grid-cols-2 gap-3">
                    <Field>
                      <FieldLabel>Port</FieldLabel>
                      <Input
                        type="number"
                        min={1}
                        max={65535}
                        value={newPort}
                        onChange={(e) => setNewPort(Number(e.target.value))}
                      />
                    </Field>
                    <Field>
                      <FieldLabel>Memory</FieldLabel>
                      <Input
                        value={newMemory}
                        onChange={(e) => setNewMemory(e.target.value)}
                        placeholder="2G"
                      />
                    </Field>
                  </div>

                  <Field>
                    <FieldLabel>Server Software</FieldLabel>
                    <Select value={newSoftware} onValueChange={(v) => v && setNewSoftware(v)}>
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectGroup>
                          {softwareList.length > 0
                            ? softwareList.map((sw) => (
                                <SelectItem key={sw.name} value={sw.name}>
                                  {sw.name}{sw.isProxy ? " (Proxy)" : ""}
                                </SelectItem>
                              ))
                            : ["PAPER","VELOCITY","PURPUR","FOLIA","FORGE","NEOFORGE","FABRIC","PUFFERFISH","LEAF","CUSTOM"].map((sw) => (
                                <SelectItem key={sw} value={sw}>{sw}</SelectItem>
                              ))
                          }
                        </SelectGroup>
                      </SelectContent>
                    </Select>
                  </Field>

                  <Field>
                    <FieldLabel>
                      Version{" "}
                      {loadingVersions && (
                        <Loader2 className="inline size-3 animate-spin ml-1" />
                      )}
                    </FieldLabel>
                    {versions && versions.stable.length > 0 ? (
                      <Select value={newVersion} onValueChange={(v) => v && setNewVersion(v)}>
                        <SelectTrigger className="w-full">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectGroup>
                            <SelectLabel>Stable</SelectLabel>
                            {versions.stable.map((v) => (
                              <SelectItem key={v} value={v}>
                                {v}{v === versions.latest ? " (latest)" : ""}
                              </SelectItem>
                            ))}
                          </SelectGroup>
                          {versions.snapshots.length > 0 && (
                            <SelectGroup>
                              <SelectLabel>Snapshots</SelectLabel>
                              {versions.snapshots.map((v) => (
                                <SelectItem key={v} value={v}>{v}</SelectItem>
                              ))}
                            </SelectGroup>
                          )}
                        </SelectContent>
                      </Select>
                    ) : (
                      <Input
                        value={newVersion}
                        onChange={(e) => setNewVersion(e.target.value)}
                        placeholder="e.g. 1.21.4"
                      />
                    )}
                  </Field>

                  {selectedSoftware?.needsModloaderVersion && (
                    <Field>
                      <FieldLabel>
                        Modloader Version{" "}
                        {loadingModloader && (
                          <Loader2 className="inline size-3 animate-spin ml-1" />
                        )}
                      </FieldLabel>
                      {modloaderVersions && modloaderVersions.stable.length > 0 ? (
                        <Select value={newModloaderVersion} onValueChange={(v) => v && setNewModloaderVersion(v)}>
                          <SelectTrigger className="w-full">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectGroup>
                              {modloaderVersions.stable.map((v) => (
                                <SelectItem key={v} value={v}>
                                  {v}{v === modloaderVersions.latest ? " (latest)" : ""}
                                </SelectItem>
                              ))}
                            </SelectGroup>
                          </SelectContent>
                        </Select>
                      ) : (
                        <Input
                          value={newModloaderVersion}
                          onChange={(e) => setNewModloaderVersion(e.target.value)}
                          placeholder="Modloader version"
                        />
                      )}
                      <FieldDescription>
                        Leave empty for latest version
                      </FieldDescription>
                    </Field>
                  )}

                  <Field>
                    <div className="flex items-center justify-between">
                      <div>
                        <FieldLabel>Proxy Enabled</FieldLabel>
                        <FieldDescription>
                          Register with Velocity proxy and patch forwarding config.
                        </FieldDescription>
                      </div>
                      <Switch
                        checked={newProxyEnabled}
                        onCheckedChange={setNewProxyEnabled}
                      />
                    </div>
                  </Field>

                  <Field>
                    <div className="flex items-center justify-between">
                      <div>
                        <FieldLabel>Restart on Crash</FieldLabel>
                        <FieldDescription>
                          Automatically restart if the process exits unexpectedly.
                        </FieldDescription>
                      </div>
                      <Switch
                        checked={newRestartOnCrash}
                        onCheckedChange={setNewRestartOnCrash}
                      />
                    </div>
                  </Field>
                </div>
                <DialogFooter>
                  <Button
                    onClick={createService}
                    disabled={creating || !newName.trim() || !newVersion}
                  >
                    {creating ? "Creating..." : "Create"}
                  </Button>
                </DialogFooter>
              </DialogContent>
            </Dialog>
    </>
  );

  return (
    <PageShell
      title="Dedicated"
      description="Standalone servers with fixed ports and user-managed directories."
      actions={headerActions}
      status={
        loading
          ? "loading"
          : error
          ? "error"
          : services.length === 0
          ? "empty"
          : "ready"
      }
      error={error}
      onRetry={load}
      skeleton="table"
      emptyState={{
        icon: BoxIcon,
        title: "No dedicated services configured",
        description:
          "Create one to manage a standalone server with a fixed port.",
      }}
    >
      <>
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-6">Name</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead>Software</TableHead>
                  <TableHead>Version</TableHead>
                  <TableHead>Port</TableHead>
                  <TableHead>Memory</TableHead>
                  <TableHead>Proxy</TableHead>
                  <TableHead>Players</TableHead>
                  <TableHead>Uptime</TableHead>
                  <TableHead className="w-12" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {services.map((s) => {
                  const isRunning =
                    s.state !== null && s.state !== "STOPPED" && s.state !== "CRASHED";
                  return (
                    <TableRow key={s.name}>
                      <TableCell className="pl-6 font-medium">{s.name}</TableCell>
                      <TableCell>
                        <Badge variant={stateBadgeVariant(s.state)}>
                          {s.state ?? "OFFLINE"}
                        </Badge>
                      </TableCell>
                      <TableCell>{s.software}</TableCell>
                      <TableCell>{s.version}</TableCell>
                      <TableCell>{s.port}</TableCell>
                      <TableCell>{s.memory}</TableCell>
                      <TableCell>
                        <Badge variant={s.proxyEnabled ? "default" : "outline"}>
                          {s.proxyEnabled ? "ON" : "OFF"}
                        </Badge>
                      </TableCell>
                      <TableCell>{s.playerCount ?? "—"}</TableCell>
                      <TableCell className="text-muted-foreground text-xs">
                        {s.uptime ?? "—"}
                      </TableCell>
                      <TableCell>
                        <DropdownMenu>
                          <DropdownMenuTrigger
                            render={
                              <Button
                                variant="ghost"
                                size="icon"
                                className="size-8"
                                disabled={actioning === s.name}
                              >
                                <MoreVertical className="size-4" />
                              </Button>
                            }
                          />
                          <DropdownMenuContent align="end">
                            {isRunning && (
                              <DropdownMenuItem onClick={() => setConsoleService(s.name)}>
                                <TerminalIcon className="mr-2 size-4" /> Open Console
                              </DropdownMenuItem>
                            )}
                            {!isRunning && (
                              <DropdownMenuItem onClick={() => doAction(s.name, "start")}>
                                <Play className="mr-2 size-4" /> Start
                              </DropdownMenuItem>
                            )}
                            {isRunning && (
                              <DropdownMenuItem onClick={() => doAction(s.name, "stop")}>
                                <Square className="mr-2 size-4" /> Stop
                              </DropdownMenuItem>
                            )}
                            {isRunning && (
                              <DropdownMenuItem onClick={() => doAction(s.name, "restart")}>
                                <RotateCw className="mr-2 size-4" /> Restart
                              </DropdownMenuItem>
                            )}
                            <DropdownMenuSeparator />
                            <DropdownMenuItem onClick={() => openEdit(s)}>
                              <Pencil className="mr-2 size-4" /> Edit
                            </DropdownMenuItem>
                            <DropdownMenuItem
                              onClick={() => setDeleteTarget(s.name)}
                              className="text-destructive"
                            >
                              <Trash2 className="mr-2 size-4" /> Delete
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </CardContent>
        </Card>

      {/* Edit Dialog */}
      <Dialog
        open={!!editTarget}
        onOpenChange={(open) => !open && setEditTarget(null)}
      >
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>Edit {editTarget?.name}</DialogTitle>
            <DialogDescription>
              Update port, memory and proxy settings. If the service is running, it
              will be stopped — restart it manually to apply changes.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="grid grid-cols-2 gap-3">
              <Field>
                <FieldLabel>Port</FieldLabel>
                <Input
                  type="number"
                  min={1}
                  max={65535}
                  value={editPort}
                  onChange={(e) => setEditPort(Number(e.target.value))}
                />
              </Field>
              <Field>
                <FieldLabel>Memory</FieldLabel>
                <Input
                  value={editMemory}
                  onChange={(e) => setEditMemory(e.target.value)}
                  placeholder="2G"
                />
              </Field>
            </div>
            <Field>
              <div className="flex items-center justify-between">
                <div>
                  <FieldLabel>Proxy Enabled</FieldLabel>
                  <FieldDescription>
                    Register with Velocity proxy.
                  </FieldDescription>
                </div>
                <Switch
                  checked={editProxyEnabled}
                  onCheckedChange={setEditProxyEnabled}
                />
              </div>
            </Field>
            <Field>
              <div className="flex items-center justify-between">
                <div>
                  <FieldLabel>Restart on Crash</FieldLabel>
                  <FieldDescription>
                    Automatically restart if the process exits unexpectedly.
                  </FieldDescription>
                </div>
                <Switch
                  checked={editRestartOnCrash}
                  onCheckedChange={setEditRestartOnCrash}
                />
              </div>
            </Field>
            <div className="rounded-md border p-3 text-xs text-muted-foreground space-y-1">
              <div>
                <span className="font-medium text-foreground">Software:</span>{" "}
                {editTarget?.software} {editTarget?.version}
              </div>
              <div>
                <span className="font-medium text-foreground">Directory:</span>{" "}
                <code className="font-mono">{editTarget?.directory}</code>
              </div>
              <div className="pt-1">
                To change software or version, delete and recreate the service.
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditTarget(null)}>
              Cancel
            </Button>
            <Button onClick={saveEdit} disabled={saving}>
              {saving ? "Saving..." : "Save"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={!!deleteTarget}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Dedicated Service</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete &apos;{deleteTarget}&apos;? The server will
              be stopped if running. The server directory itself will not be deleted. This
              action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={confirmDelete} disabled={deleting}>
              {deleting ? "Deleting..." : "Delete"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <ServiceConsoleSheet
        serviceName={consoleService}
        open={consoleService !== null}
        onOpenChange={(o) => {
          if (!o) setConsoleService(null);
        }}
      />
      </>
    </PageShell>
  );
}
