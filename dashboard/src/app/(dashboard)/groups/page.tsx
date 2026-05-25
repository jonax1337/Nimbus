"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Card, CardContent } from "@/components/ui/card";
import { PageShell } from "@/components/page-shell";
import { useApiResource } from "@/hooks/use-api-resource";
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
import { apiFetch, apiUpload } from "@/lib/api";
import { toast } from "sonner";
import { Plus, Loader2, Package, FolderTreeIcon, Upload, MoreHorizontal, Pencil, Trash2 } from "@/lib/icons";
import { Field, FieldLabel, FieldDescription } from "@/components/ui/field";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { GroupEditDialog } from "@/components/group-edit-dialog";
import { GroupDeleteDialog } from "@/components/group-delete-dialog";

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

interface ModpackImportResponse {
  success: boolean;
  message: string;
  groupName: string;
  filesDownloaded: number;
  filesFailed: number;
}

interface Group {
  name: string;
  type: string;
  software: string;
  version: string;
  resources: { memory: string; maxPlayers: number };
  scaling: { minInstances: number; maxInstances: number };
  activeInstances: number;
}

interface GroupListResponse {
  groups: Group[];
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

export default function GroupsPage() {
  const {
    data: groupsResp,
    loading,
    error,
    refetch: load,
  } = useApiResource<GroupListResponse>("/api/groups");
  const groups = groupsResp?.groups ?? [];
  const [createOpen, setCreateOpen] = useState(false);

  // Create form state
  const [softwareList, setSoftwareList] = useState<SoftwareInfo[]>([]);
  const [newName, setNewName] = useState("");
  const [newType, setNewType] = useState("DYNAMIC");
  const [newSoftware, setNewSoftware] = useState("PAPER");
  const [newVersion, setNewVersion] = useState("");
  const [newModloaderVersion, setNewModloaderVersion] = useState("");
  const [newMemory, setNewMemory] = useState("1G");
  const [newMinInstances, setNewMinInstances] = useState(1);
  const [newMaxInstances, setNewMaxInstances] = useState(4);
  const [versions, setVersions] = useState<VersionListResponse | null>(null);
  const [modloaderVersions, setModloaderVersions] = useState<VersionListResponse | null>(null);
  const [loadingVersions, setLoadingVersions] = useState(false);
  const [loadingModloader, setLoadingModloader] = useState(false);
  const [creating, setCreating] = useState(false);

  const selectedSoftware = softwareList.find((s) => s.name === newSoftware);

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

  async function createGroup() {
    if (!newName.trim()) return;
    setCreating(true);
    try {
      await apiFetch("/api/groups", {
        method: "POST",
        body: JSON.stringify({
          name: newName.trim(),
          type: newType,
          template: newName.trim().toLowerCase().replace(/[^a-z0-9_-]/g, ""),
          software: newSoftware,
          version: newVersion,
          modloaderVersion: newModloaderVersion,
          memory: newMemory,
          minInstances: newMinInstances,
          maxInstances: newMaxInstances,
        }),
      });
      toast.success(`Group '${newName}' created`);
      setCreateOpen(false);
      setNewName("");
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to create group");
    } finally {
      setCreating(false);
    }
  }

  // Modpack import state
  const [importOpen, setImportOpen] = useState(false);
  const [importMode, setImportMode] = useState<"source" | "upload">("source");
  const [importSource, setImportSource] = useState("");
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importGroupName, setImportGroupName] = useState("");
  const [importType, setImportType] = useState("STATIC");
  const [importMemory, setImportMemory] = useState("4G");
  const [importMinInstances, setImportMinInstances] = useState(1);
  const [importMaxInstances, setImportMaxInstances] = useState(1);
  const [importInfo, setImportInfo] = useState<ModpackInfo | null>(null);
  const [resolving, setResolving] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importProgress, setImportProgress] = useState("");
  // -1 = indeterminate (e.g. server-side download phase); 0..100 = upload %.
  const [importPercent, setImportPercent] = useState<number>(-1);

  function resetImportState() {
    setImportSource("");
    setImportFile(null);
    setImportGroupName("");
    setImportType("STATIC");
    setImportMemory("4G");
    setImportMinInstances(1);
    setImportMaxInstances(1);
    setImportInfo(null);
    setImportProgress("");
    setImportPercent(-1);
    setImportMode("source");
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
      if (!importGroupName) setImportGroupName(info.name.replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 20) || "modpack");
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
    // Derive group name from filename
    const name = file.name
      .replace(/\.zip$|\.mrpack$/i, "")
      .replace(/[-_]?[Ss]erver[Ff]iles[-_]?/g, "")
      .replace(/[-_]?[Ss]erver[-_]?[Pp]ack[-_]?/g, "")
      .replace(/[^a-zA-Z0-9_-]/g, "")
      .slice(0, 20) || "modpack";
    if (!importGroupName) setImportGroupName(name);
  }

  async function waitForGroupAppears(
    name: string,
    timeoutMs = 120_000,
    intervalMs = 4_000,
  ): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      try {
        const res = await apiFetch<{ groups: Array<{ name: string }> }>(
          "/api/groups",
          { silent: true },
        );
        if (res.groups.some((g) => g.name === name)) return true;
      } catch {
        // Ignore transient errors; keep polling.
      }
      await new Promise((r) => setTimeout(r, intervalMs));
    }
    return false;
  }

  async function importModpack() {
    if (!importGroupName.trim()) return;
    setImporting(true);
    setImportProgress("Importing...");
    setImportPercent(-1);
    const groupName = importGroupName.trim();
    try {
      if (importMode === "upload" && importFile) {
        setImportProgress(`Uploading ${importFile.name}…`);
        setImportPercent(0);
        const params = new URLSearchParams({
          groupName,
          type: importType,
          memory: importMemory,
          minInstances: String(importMinInstances),
          maxInstances: String(importMaxInstances),
          fileName: importFile.name,
        });
        let result: ModpackImportResponse;
        try {
          result = await apiUpload<ModpackImportResponse>(
            `/api/modpacks/upload?${params.toString()}`,
            importFile,
            (uploaded, total) => {
              const pct = Math.round((uploaded / total) * 100);
              setImportPercent(pct);
              setImportProgress(
                pct < 100
                  ? `Uploading ${importFile.name}… ${pct}%`
                  : `Installing on controller…`
              );
              if (pct >= 100) setImportPercent(-1);
            }
          );
        } catch (err) {
          // Large server packs (1 GB+) take 30–60 s to extract + install
          // modloader after bytes-upload completes. Idle timeouts between the
          // browser and the controller can drop the connection before the
          // final HTTP response, even though the import succeeded. Detect
          // this and wait for the group to materialise.
          const msg = err instanceof Error ? err.message : String(err);
          const looksLikeNetworkDrop =
            err instanceof TypeError ||
            /Failed to fetch|NetworkError|disconnected|aborted|ERR_/i.test(msg);
          if (!looksLikeNetworkDrop) throw err;

          setImportProgress("Connection dropped — waiting for server to finish import…");
          const appeared = await waitForGroupAppears(groupName);
          if (!appeared) {
            throw new Error(
              "Upload connection dropped and the group did not appear within 2 min. Check the controller logs.",
            );
          }
          result = {
            success: true,
            message: `Group '${groupName}' imported (response timed out, but group is registered).`,
            groupName,
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
        // Source-based import (slug, URL)
        setImportProgress("Downloading and installing...");
        const result = await apiFetch<ModpackImportResponse>("/api/modpacks/import", {
          method: "POST",
          body: JSON.stringify({
            source: importSource.trim(),
            groupName: importGroupName.trim(),
            type: importType,
            memory: importMemory,
            minInstances: importMinInstances,
            maxInstances: importMaxInstances,
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
      setImportPercent(-1);
    }
  }

  const canImport = importGroupName.trim() && (
    (importMode === "source" && importSource.trim()) ||
    (importMode === "upload" && importFile)
  );

  const [editTarget, setEditTarget] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);

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
                <DialogTitle>Import Modpack</DialogTitle>
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
                      onClick={() => document.getElementById("modpack-file-input")?.click()}
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
                          if (!importGroupName) setImportGroupName(name);
                        } else {
                          toast.error("Please drop a .zip or .mrpack file");
                        }
                      }}
                    >
                      <input
                        id="modpack-file-input"
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
                      <Badge variant="secondary">{importInfo.source === "SERVER_PACK" ? "Server Pack" : importInfo.source === "CURSEFORGE_API" ? "CurseForge" : "Modrinth"}</Badge>
                    </div>
                    <div className="text-muted-foreground">
                      {importInfo.modloader} {importInfo.modloaderVersion} / MC {importInfo.mcVersion}
                    </div>
                    <div className="text-muted-foreground">
                      {importInfo.serverFiles} server mods{importInfo.totalFiles !== importInfo.serverFiles ? ` (${importInfo.totalFiles} total)` : ""}
                    </div>
                  </div>
                )}

                <Field>
                  <FieldLabel>Group Name</FieldLabel>
                  <Input
                    value={importGroupName}
                    onChange={(e) => setImportGroupName(e.target.value)}
                    placeholder="e.g. ATM10"
                  />
                </Field>

                <Field>
                  <FieldLabel>Type</FieldLabel>
                  <Select value={importType} onValueChange={(v) => v && setImportType(v)}>
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="STATIC">Static (persistent world)</SelectItem>
                      <SelectItem value="DYNAMIC">Dynamic (fresh from template)</SelectItem>
                    </SelectContent>
                  </Select>
                </Field>

                <div className="grid grid-cols-3 gap-3">
                  <Field>
                    <FieldLabel>Memory</FieldLabel>
                    <Input
                      value={importMemory}
                      onChange={(e) => setImportMemory(e.target.value)}
                      placeholder="4G"
                    />
                  </Field>
                  <Field>
                    <FieldLabel>Min Instances</FieldLabel>
                    <Input
                      type="number"
                      min={0}
                      value={importMinInstances}
                      onChange={(e) => setImportMinInstances(Number(e.target.value))}
                    />
                  </Field>
                  <Field>
                    <FieldLabel>Max Instances</FieldLabel>
                    <Input
                      type="number"
                      min={1}
                      value={importMaxInstances}
                      onChange={(e) => setImportMaxInstances(Number(e.target.value))}
                    />
                  </Field>
                </div>
              </div>
              {importProgress && (
                <div className="space-y-1.5 px-1">
                  <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
                    <span className="flex items-center gap-2">
                      <Loader2 className="size-3.5 animate-spin" />
                      {importProgress}
                    </span>
                    {importPercent >= 0 && (
                      <span className="font-mono">{importPercent}%</span>
                    )}
                  </div>
                  <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
                    {importPercent >= 0 ? (
                      <div
                        className="h-full bg-primary transition-[width] duration-150 ease-out"
                        style={{ width: `${importPercent}%` }}
                      />
                    ) : (
                      <div className="h-full w-1/3 animate-pulse bg-primary/60" />
                    )}
                  </div>
                </div>
              )}
              <DialogFooter>
                <Button onClick={importModpack} disabled={importing || !canImport}>
                  {importing ? "Importing…" : importMode === "upload" ? "Upload & Import" : "Import"}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
          <Dialog open={createOpen} onOpenChange={setCreateOpen}>
            <DialogTrigger
              render={
                <Button>
                  <Plus className="mr-1 size-4" /> New Group
                </Button>
              }
            />
          <DialogContent className="max-w-lg max-h-[85vh] overflow-y-auto">
            <DialogHeader>
              <DialogTitle>Create Group</DialogTitle>
              <DialogDescription>
                Configure a new server group.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-4 py-2">
              <Field>
                <FieldLabel>Group Name</FieldLabel>
                <Input
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  placeholder="e.g. BedWars"
                />
              </Field>

              <Field>
                <FieldLabel>Type</FieldLabel>
                <Select value={newType} onValueChange={(v) => v && setNewType(v)}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      <SelectItem value="DYNAMIC">Dynamic (fresh from template)</SelectItem>
                      <SelectItem value="STATIC">Static (persistent world)</SelectItem>
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </Field>

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

              <div className="grid grid-cols-3 gap-3">
                <Field>
                  <FieldLabel>Memory</FieldLabel>
                  <Input
                    value={newMemory}
                    onChange={(e) => setNewMemory(e.target.value)}
                    placeholder="1G"
                  />
                </Field>
                <Field>
                  <FieldLabel>Min Instances</FieldLabel>
                  <Input
                    type="number"
                    min={0}
                    value={newMinInstances}
                    onChange={(e) => setNewMinInstances(Number(e.target.value))}
                  />
                </Field>
                <Field>
                  <FieldLabel>Max Instances</FieldLabel>
                  <Input
                    type="number"
                    min={1}
                    value={newMaxInstances}
                    onChange={(e) => setNewMaxInstances(Number(e.target.value))}
                  />
                </Field>
              </div>
            </div>
            <DialogFooter>
              <Button
                onClick={createGroup}
                disabled={creating || !newName.trim() || !newVersion}
              >
                {creating ? "Creating..." : "Create Group"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
    </>
  );

  return (
    <PageShell
      title="Groups"
      description={`${groups.length} group${
        groups.length === 1 ? "" : "s"
      } configured · dynamic and static servers.`}
      actions={headerActions}
      status={
        loading
          ? "loading"
          : error
          ? "error"
          : groups.length === 0
          ? "empty"
          : "ready"
      }
      error={error}
      onRetry={load}
      skeleton="table"
      emptyState={{
        icon: FolderTreeIcon,
        title: "No groups configured",
        description:
          "Create a group to start running lobbies or game servers.",
      }}
    >
      <>
        <Card>
          <CardContent className="p-0">
            <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="pl-6">Name</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Software</TableHead>
                <TableHead>Version</TableHead>
                <TableHead>Memory</TableHead>
                <TableHead className="text-right">Instances</TableHead>
                <TableHead className="text-right">Max Players</TableHead>
                <TableHead className="w-12" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {groups.map((g) => (
                <TableRow key={g.name}>
                  <TableCell className="pl-6">
                    <Link
                      href={`/groups/${g.name}`}
                      className="font-medium hover:underline"
                    >
                      {g.name}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline">{g.type}</Badge>
                  </TableCell>
                  <TableCell>{g.software}</TableCell>
                  <TableCell>{g.version}</TableCell>
                  <TableCell>{g.resources.memory}</TableCell>
                  <TableCell className="text-right">
                    {g.activeInstances} / {g.scaling.minInstances}-
                    {g.scaling.maxInstances}
                  </TableCell>
                  <TableCell className="text-right">
                    {g.resources.maxPlayers}
                  </TableCell>
                  <TableCell>
                    <DropdownMenu>
                      <DropdownMenuTrigger
                        render={
                          <Button variant="ghost" size="icon" className="size-8">
                            <MoreHorizontal className="size-4" />
                            <span className="sr-only">Actions</span>
                          </Button>
                        }
                      />
                      <DropdownMenuContent align="end">
                        <DropdownMenuItem onClick={() => setEditTarget(g.name)}>
                          <Pencil className="size-4" /> Edit
                        </DropdownMenuItem>
                        <DropdownMenuItem
                          variant="destructive"
                          onClick={() => setDeleteTarget(g.name)}
                        >
                          <Trash2 className="size-4" /> Delete
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          </CardContent>
        </Card>

        <GroupEditDialog
          groupName={editTarget}
          open={!!editTarget}
          onOpenChange={(open) => !open && setEditTarget(null)}
          onSaved={load}
        />
        <GroupDeleteDialog
          groupName={deleteTarget}
          open={!!deleteTarget}
          onOpenChange={(open) => !open && setDeleteTarget(null)}
          onDeleted={load}
        />
      </>
    </PageShell>
  );
}
