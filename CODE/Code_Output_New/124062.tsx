/*
Story ID: 124062
Title: Sculpt.ag: mapping saved data to the layer folder structure for the files
Language: TypeScript — the repo is a Vite React TypeScript UI codebase and the story title points to a UI feature around folder/layer mapping.
What this code does: Renders a folder-navigation style tree for saved Sculpt.ag data by mapping flat file records into layer-based folders, with deterministic grouping, file counts, and path generation.
Assumptions: Work item response included only basic fields; description, acceptance criteria, comments, links, and attachments were missing. The exact target entity/module was not present in the repo sample, so the folder mapping model and helpers are implemented here as assumptions.
*/

import React, { useMemo, useState } from "react";

export type SculptAgSavedFileRecord = {
  id: string;
  fileName: string;
  layerName?: string | null;
  relativePath?: string | null;
  savedPath?: string | null;
  category?: string | null;
  tags?: string[] | null;
  extension?: string | null;
  sizeInBytes?: number | null;
  updatedAt?: string | null;
};

export type SculptAgLayerNodeType = "root" | "layer" | "folder" | "file";

export type SculptAgLayerTreeNode = {
  id: string;
  name: string;
  type: SculptAgLayerNodeType;
  fullPath: string;
  level: number;
  file?: SculptAgSavedFileRecord;
  fileCount: number;
  children: SculptAgLayerTreeNode[];
};

export interface SculptAgLayerFolderMappingProps {
  title?: string;
  dataBinding?: string;
  staticData?: SculptAgSavedFileRecord[];
  showCounts?: boolean;
  defaultExpanded?: boolean;
}

const DEFAULT_LAYER_NAME = "Unassigned Layer";
const FILE_NODE_PREFIX = "file";
const FOLDER_NODE_PREFIX = "folder";
const LAYER_NODE_PREFIX = "layer";
const ROOT_NODE_ID = "sculpt-ag-root";

function normalizeSegment(value: string | null | undefined, fallback: string): string {
  const trimmed = String(value ?? "").trim();
  if (!trimmed) return fallback;
  return trimmed
    .replace(/\\/g, "/")
    .split("/")
    .map((segment) => segment.trim())
    .filter(Boolean)
    .join("/");
}

function splitIntoSegments(record: SculptAgSavedFileRecord): string[] {
  const layerSegment = normalizeSegment(record.layerName, DEFAULT_LAYER_NAME);
  const rawPath = normalizeSegment(
    record.relativePath || record.savedPath || record.category,
    "",
  );

  const pathSegments = rawPath
    ? rawPath
        .split("/")
        .map((segment) => segment.trim())
        .filter(Boolean)
    : [];

  const safeFileName = String(record.fileName || "").trim() || "unnamed-file";
  return [layerSegment, ...pathSegments, safeFileName];
}

function buildTree(records: SculptAgSavedFileRecord[]): SculptAgLayerTreeNode {
  const root: SculptAgLayerTreeNode = {
    id: ROOT_NODE_ID,
    name: "Layers",
    type: "root",
    fullPath: "",
    level: 0,
    fileCount: 0,
    children: [],
  };

  const ensureChild = (
    parent: SculptAgLayerTreeNode,
    name: string,
    type: SculptAgLayerNodeType,
    fullPath: string,
    file?: SculptAgSavedFileRecord,
  ): SculptAgLayerTreeNode => {
    const existing = parent.children.find(
      (child) => child.name === name && child.type === type,
    );
    if (existing) return existing;

    const child: SculptAgLayerTreeNode = {
      id:
        type === "file"
          ? `${FILE_NODE_PREFIX}:${file?.id || fullPath}`
          : type === "layer"
            ? `${LAYER_NODE_PREFIX}:${fullPath}`
            : `${FOLDER_NODE_PREFIX}:${fullPath}`,
      name,
      type,
      fullPath,
      level: parent.level + 1,
      file,
      fileCount: type === "file" ? 1 : 0,
      children: [],
    };

    parent.children.push(child);
    return child;
  };

  for (const record of records) {
    const segments = splitIntoSegments(record);
    let current = root;

    segments.forEach((segment, index) => {
      const isLayer = index === 0;
      const isFile = index === segments.length - 1;
      const nextPath = [current.fullPath, segment].filter(Boolean).join("/");

      current = ensureChild(
        current,
        segment,
        isFile ? "file" : isLayer ? "layer" : "folder",
        nextPath,
        isFile ? record : undefined,
      );
    });
  }

  const sortAndCount = (node: SculptAgLayerTreeNode): number => {
    if (node.type === "file") return 1;

    node.children.sort((left, right) => {
      if (left.type === "file" && right.type !== "file") return 1;
      if (left.type !== "file" && right.type === "file") return -1;
      return left.name.localeCompare(right.name, undefined, { sensitivity: "base" });
    });

    node.fileCount = node.children.reduce(
      (total, child) => total + sortAndCount(child),
      0,
    );

    return node.fileCount;
  };

  sortAndCount(root);
  return root;
}

function formatSize(sizeInBytes?: number | null): string {
  if (!sizeInBytes || sizeInBytes <= 0) return "";
  if (sizeInBytes < 1024) return `${sizeInBytes} B`;
  if (sizeInBytes < 1024 * 1024) return `${(sizeInBytes / 1024).toFixed(1)} KB`;
  return `${(sizeInBytes / (1024 * 1024)).toFixed(1)} MB`;
}

function inferStaticData(
  dataBinding: string | undefined,
  staticData: SculptAgSavedFileRecord[] | undefined,
): SculptAgSavedFileRecord[] {
  if (Array.isArray(staticData) && staticData.length > 0) {
    return staticData;
  }

  if (!dataBinding) return [];

  try {
    const parsed = JSON.parse(dataBinding) as unknown;
    return Array.isArray(parsed) ? (parsed as SculptAgSavedFileRecord[]) : [];
  } catch {
    return [];
  }
}

function TreeRow({
  node,
  showCounts,
  defaultExpanded,
}: {
  node: SculptAgLayerTreeNode;
  showCounts: boolean;
  defaultExpanded: boolean;
}): React.ReactElement {
  const [expanded, setExpanded] = useState<boolean>(defaultExpanded);
  const hasChildren = node.children.length > 0;
  const isFile = node.type === "file";

  return (
    <div>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 8,
          padding: "6px 8px",
          paddingLeft: `${node.level * 16}px`,
          borderRadius: 6,
        }}
      >
        {hasChildren ? (
          <button
            type="button"
            onClick={() => setExpanded((current) => !current)}
            style={{
              border: "none",
              background: "transparent",
              cursor: "pointer",
              fontSize: 12,
              width: 18,
            }}
            aria-label={expanded ? `Collapse ${node.name}` : `Expand ${node.name}`}
          >
            {expanded ? "▾" : "▸"}
          </button>
        ) : (
          <span style={{ width: 18, display: "inline-block" }} />
        )}

        <span>
          {node.type === "layer" ? "🧱" : node.type === "folder" ? "📁" : node.type === "file" ? "📄" : "🗂️"}
        </span>

        <span style={{ fontWeight: isFile ? 400 : 600 }}>{node.name}</span>

        {showCounts && !isFile ? (
          <span style={{ color: "#64748b", fontSize: 12 }}>({node.fileCount})</span>
        ) : null}

        {isFile && node.file ? (
          <span style={{ color: "#64748b", fontSize: 12 }}>
            {formatSize(node.file.sizeInBytes)}
            {node.file.updatedAt ? ` • ${node.file.updatedAt}` : ""}
          </span>
        ) : null}
      </div>

      {hasChildren && expanded
        ? node.children.map((child) => (
            <TreeRow
              key={child.id}
              node={child}
              showCounts={showCounts}
              defaultExpanded={defaultExpanded}
            />
          ))
        : null}
    </div>
  );
}

const sampleData: SculptAgSavedFileRecord[] = [
  {
    id: "1",
    fileName: "customer_master.csv",
    layerName: "Bronze",
    relativePath: "ingestion/raw/customer",
    sizeInBytes: 24110,
    updatedAt: "2026-11-20T10:30:00Z",
  },
  {
    id: "2",
    fileName: "orders_delta.parquet",
    layerName: "Silver",
    relativePath: "transformation/sales/orders",
    sizeInBytes: 184200,
    updatedAt: "2026-11-20T11:00:00Z",
  },
  {
    id: "3",
    fileName: "revenue_summary.json",
    layerName: "Gold",
    relativePath: "analytics/finance",
    sizeInBytes: 4120,
    updatedAt: "2026-11-20T11:30:00Z",
  },
];

const SculptAgLayerFolderMapping: React.FC<SculptAgLayerFolderMappingProps> = ({
  title = "Sculpt.ag Layer Folder Mapping",
  dataBinding,
  staticData,
  showCounts = true,
  defaultExpanded = true,
}) => {
  const records = useMemo(() => {
    const resolved = inferStaticData(dataBinding, staticData);
    return resolved.length > 0 ? resolved : sampleData;
  }, [dataBinding, staticData]);

  const tree = useMemo(() => buildTree(records), [records]);

  return (
    <div
      style={{
        border: "1px solid #e2e8f0",
        borderRadius: 12,
        padding: 16,
        background: "#ffffff",
        fontFamily: "Inter, sans-serif",
      }}
    >
      <div style={{ marginBottom: 12 }}>
        <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700 }}>{title}</h3>
        <div style={{ color: "#64748b", fontSize: 12, marginTop: 4 }}>
          Saved files are mapped into layer-first folder paths for consistent navigation.
        </div>
      </div>

      {tree.children.length === 0 ? (
        <div style={{ color: "#64748b", fontSize: 14 }}>No saved files available.</div>
      ) : (
        tree.children.map((child) => (
          <TreeRow
            key={child.id}
            node={child}
            showCounts={showCounts}
            defaultExpanded={defaultExpanded}
          />
        ))
      )}
    </div>
  );
};

export default SculptAgLayerFolderMapping;
