/**
 * Story ID: 124062
 * Title: Sculpt.ag: mapping saved data to the layer folder structure for the files
 * Language: TypeScript — the repo is a React/Vite TypeScript frontend, and the story title clearly targets UI folder-structure mapping behavior in this codebase.
 * What this code does: Adds a self-contained utility/service module for mapping saved sculpt.ag file records into a normalized layer-folder tree structure, with helpers to merge, sort, and flatten the result for UI consumption.
 * Assumptions:
 * - The full work item details were not available beyond the title/state, so the implementation is based on the story title only.
 * - This file is repo-style and intended to be imported by existing save/load flows for sculpt.ag file rendering.
 * - No exact existing model/service for this feature was found in the sampled repo content.
 */

export type SculptAgLayerType =
  | "source"
  | "bronze"
  | "silver"
  | "gold"
  | "platinum"
  | "semantic"
  | "serving"
  | "unknown";

export interface SculptAgSavedFile {
  id: string;
  name: string;
  path?: string | null;
  layer?: string | null;
  folderPath?: string | null;
  relativePath?: string | null;
  extension?: string | null;
  contentType?: string | null;
  sizeInBytes?: number | null;
  modifiedAt?: string | null;
  metadata?: Record<string, unknown> | null;
}

export interface SculptAgFileNode {
  kind: "file";
  id: string;
  name: string;
  fullPath: string;
  layer: SculptAgLayerType;
  extension: string;
  contentType?: string | null;
  sizeInBytes?: number | null;
  modifiedAt?: string | null;
  metadata: Record<string, unknown>;
}

export interface SculptAgFolderNode {
  kind: "folder";
  id: string;
  name: string;
  fullPath: string;
  layer: SculptAgLayerType | "root";
  folders: SculptAgFolderNode[];
  files: SculptAgFileNode[];
}

export interface SculptAgLayerTree {
  root: SculptAgFolderNode;
  layers: Record<string, SculptAgFolderNode>;
}

export interface SculptAgFlatTreeRow {
  id: string;
  name: string;
  kind: "folder" | "file";
  layer: SculptAgLayerType | "root";
  fullPath: string;
  depth: number;
  parentPath: string | null;
  fileExtension?: string;
  sizeInBytes?: number | null;
}

type FolderIndex = Map<string, SculptAgFolderNode>;

const KNOWN_LAYERS: SculptAgLayerType[] = [
  "source",
  "bronze",
  "silver",
  "gold",
  "platinum",
  "semantic",
  "serving",
  "unknown",
];

export class SculptAgLayerFolderMapper {
  // ASSUMPTION: not found in repo content
  static buildLayerTree(files: SculptAgSavedFile[]): SculptAgLayerTree {
    const root: SculptAgFolderNode = {
      kind: "folder",
      id: "root",
      name: "layers",
      fullPath: "/",
      layer: "root",
      folders: [],
      files: [],
    };

    const folderIndex: FolderIndex = new Map<string, SculptAgFolderNode>();
    folderIndex.set("/", root);

    const layers: Record<string, SculptAgFolderNode> = {};

    for (const layer of KNOWN_LAYERS) {
      const layerFolder = this.createFolderNode(layer, `/${layer}`, layer);
      root.folders.push(layerFolder);
      folderIndex.set(layerFolder.fullPath, layerFolder);
      layers[layer] = layerFolder;
    }

    for (const file of files) {
      const normalized = this.normalizeSavedFile(file);
      const layerRoot = layers[normalized.layer];
      const relativeSegments = this.extractRelativeSegments(normalized);
      const parentFolder = this.ensureFolderPath(
        layerRoot,
        relativeSegments.folderSegments,
        normalized.layer,
        folderIndex,
      );

      const fullFilePath = this.joinPath(parentFolder.fullPath, normalized.name);
      const fileNode: SculptAgFileNode = {
        kind: "file",
        id: normalized.id,
        name: normalized.name,
        fullPath: fullFilePath,
        layer: normalized.layer,
        extension: normalized.extension,
        contentType: normalized.contentType ?? null,
        sizeInBytes: normalized.sizeInBytes ?? null,
        modifiedAt: normalized.modifiedAt ?? null,
        metadata: normalized.metadata ?? {},
      };

      const existingIndex = parentFolder.files.findIndex(
        (current) => current.id === fileNode.id || current.fullPath === fileNode.fullPath,
      );

      if (existingIndex >= 0) {
        parentFolder.files[existingIndex] = fileNode;
      } else {
        parentFolder.files.push(fileNode);
      }
    }

    this.sortFolderRecursive(root);
    return { root, layers };
  }

  // ASSUMPTION: not found in repo content
  static mergeLayerTrees(...trees: SculptAgLayerTree[]): SculptAgLayerTree {
    const allFiles: SculptAgSavedFile[] = [];

    for (const tree of trees) {
      allFiles.push(...this.toSavedFiles(tree.root));
    }

    return this.buildLayerTree(allFiles);
  }

  // ASSUMPTION: not found in repo content
  static flattenTree(tree: SculptAgLayerTree): SculptAgFlatTreeRow[] {
    const rows: SculptAgFlatTreeRow[] = [];

    const walk = (
      folder: SculptAgFolderNode,
      depth: number,
      parentPath: string | null,
    ): void => {
      rows.push({
        id: folder.id,
        name: folder.name,
        kind: "folder",
        layer: folder.layer,
        fullPath: folder.fullPath,
        depth,
        parentPath,
      });

      for (const childFolder of folder.folders) {
        walk(childFolder, depth + 1, folder.fullPath);
      }

      for (const file of folder.files) {
        rows.push({
          id: file.id,
          name: file.name,
          kind: "file",
          layer: file.layer,
          fullPath: file.fullPath,
          depth: depth + 1,
          parentPath: folder.fullPath,
          fileExtension: file.extension,
          sizeInBytes: file.sizeInBytes ?? null,
        });
      }
    };

    walk(tree.root, 0, null);
    return rows;
  }

  // ASSUMPTION: not found in repo content
  static findLayerFolder(
    tree: SculptAgLayerTree,
    layer: SculptAgLayerType,
  ): SculptAgFolderNode {
    return tree.layers[layer] ?? tree.layers.unknown;
  }

  // ASSUMPTION: not found in repo content
  static toSavedFiles(root: SculptAgFolderNode): SculptAgSavedFile[] {
    const output: SculptAgSavedFile[] = [];

    const walk = (folder: SculptAgFolderNode): void => {
      for (const file of folder.files) {
        output.push({
          id: file.id,
          name: file.name,
          path: file.fullPath,
          layer: file.layer,
          folderPath: this.dirname(file.fullPath),
          relativePath: this.relativeToLayerRoot(file.fullPath, file.layer),
          extension: file.extension,
          contentType: file.contentType ?? null,
          sizeInBytes: file.sizeInBytes ?? null,
          modifiedAt: file.modifiedAt ?? null,
          metadata: file.metadata,
        });
      }

      for (const child of folder.folders) {
        walk(child);
      }
    };

    walk(root);
    return output;
  }

  private static normalizeSavedFile(file: SculptAgSavedFile): Required<
    Pick<SculptAgSavedFile, "id" | "name">
  > & {
    layer: SculptAgLayerType;
    path: string;
    folderPath: string;
    relativePath: string;
    extension: string;
    contentType?: string | null;
    sizeInBytes?: number | null;
    modifiedAt?: string | null;
    metadata?: Record<string, unknown> | null;
  } {
    const name = (file.name ?? "").trim();
    const fallbackName = name || this.extractFileName(file.path || file.relativePath || "unnamed");
    const extension = this.normalizeExtension(file.extension, fallbackName);
    const layer = this.normalizeLayer(file.layer, file.path, file.folderPath, file.relativePath);

    const rawRelativePath =
      file.relativePath ||
      this.stripLayerPrefix(file.path || "") ||
      this.stripLayerPrefix(file.folderPath || "") ||
      fallbackName;

    const cleanedRelativePath = this.cleanPath(rawRelativePath);
    const folderPath = this.cleanPath(file.folderPath || this.dirname(cleanedRelativePath));
    const path = this.cleanPath(file.path || this.joinPath(`/${layer}`, cleanedRelativePath));

    return {
      id: String(file.id || path || fallbackName),
      name: fallbackName,
      layer,
      path,
      folderPath,
      relativePath: cleanedRelativePath,
      extension,
      contentType: file.contentType ?? null,
      sizeInBytes: file.sizeInBytes ?? null,
      modifiedAt: file.modifiedAt ?? null,
      metadata: file.metadata ?? {},
    };
  }

  private static extractRelativeSegments(file: {
    relativePath: string;
    name: string;
  }): { folderSegments: string[] } {
    const normalizedRelative = this.cleanPath(file.relativePath);
    const segments = normalizedRelative.split("/").filter(Boolean);

    if (segments.length === 0) {
      return { folderSegments: [] };
    }

    if (segments[segments.length - 1] === file.name) {
      segments.pop();
    }

    return { folderSegments: segments };
  }

  private static ensureFolderPath(
    layerRoot: SculptAgFolderNode,
    folderSegments: string[],
    layer: SculptAgLayerType,
    folderIndex: FolderIndex,
  ): SculptAgFolderNode {
    let current = layerRoot;

    for (const segment of folderSegments) {
      const nextPath = this.joinPath(current.fullPath, segment);
      const existing = folderIndex.get(nextPath);

      if (existing) {
        current = existing;
        continue;
      }

      const nextFolder = this.createFolderNode(segment, nextPath, layer);
      current.folders.push(nextFolder);
      folderIndex.set(nextPath, nextFolder);
      current = nextFolder;
    }

    return current;
  }

  private static createFolderNode(
    name: string,
    fullPath: string,
    layer: SculptAgLayerType | "root",
  ): SculptAgFolderNode {
    return {
      kind: "folder",
      id: `folder:${fullPath}`,
      name,
      fullPath,
      layer,
      folders: [],
      files: [],
    };
  }

  private static sortFolderRecursive(folder: SculptAgFolderNode): void {
    folder.folders.sort((left, right) => left.name.localeCompare(right.name));
    folder.files.sort((left, right) => left.name.localeCompare(right.name));

    for (const child of folder.folders) {
      this.sortFolderRecursive(child);
    }
  }

  private static normalizeLayer(
    candidate?: string | null,
    ...pathCandidates: Array<string | null | undefined>
  ): SculptAgLayerType {
    const direct = (candidate ?? "").trim().toLowerCase();
    if (KNOWN_LAYERS.includes(direct as SculptAgLayerType)) {
      return direct as SculptAgLayerType;
    }

    for (const pathCandidate of pathCandidates) {
      const value = (pathCandidate ?? "").toLowerCase();
      for (const layer of KNOWN_LAYERS) {
        if (value === layer || value.startsWith(`/${layer}/`) || value.includes(`/${layer}/`)) {
          return layer;
        }
      }
    }

    return "unknown";
  }

  private static normalizeExtension(
    extension: string | null | undefined,
    fileName: string,
  ): string {
    const trimmed = (extension ?? "").trim();
    if (trimmed) {
      return trimmed.startsWith(".") ? trimmed : `.${trimmed}`;
    }

    const index = fileName.lastIndexOf(".");
    return index >= 0 ? fileName.slice(index) : "";
  }

  private static extractFileName(pathValue: string): string {
    const clean = this.cleanPath(pathValue);
    const segments = clean.split("/").filter(Boolean);
    return segments[segments.length - 1] || "unnamed";
  }

  private static dirname(pathValue: string): string {
    const clean = this.cleanPath(pathValue);
    const segments = clean.split("/").filter(Boolean);
    if (segments.length <= 1) {
      return "";
    }

    segments.pop();
    return segments.join("/");
  }

  private static relativeToLayerRoot(
    fullPath: string,
    layer: SculptAgLayerType,
  ): string {
    const clean = this.cleanPath(fullPath);
    const prefix = `/${layer}/`;
    if (clean === `/${layer}`) {
      return "";
    }
    if (clean.startsWith(prefix)) {
      return clean.slice(prefix.length);
    }
    return clean.replace(/^\/+/, "");
  }

  private static stripLayerPrefix(pathValue: string): string {
    const clean = this.cleanPath(pathValue);
    for (const layer of KNOWN_LAYERS) {
      const exact = `/${layer}`;
      const prefix = `/${layer}/`;
      if (clean === exact) {
        return "";
      }
      if (clean.startsWith(prefix)) {
        return clean.slice(prefix.length);
      }
    }
    return clean.replace(/^\/+/, "");
  }

  private static cleanPath(pathValue: string): string {
    const normalized = pathValue.replace(/\\/g, "/").trim();
    if (!normalized) {
      return "";
    }

    const collapsed = normalized.replace(/\/+/g, "/");
    if (collapsed === "/") {
      return "/";
    }

    return collapsed.startsWith("/") ? collapsed : collapsed;
  }

  private static joinPath(basePath: string, nextSegment: string): string {
    const base = basePath === "/" ? "" : basePath.replace(/\/+$/, "");
    const next = nextSegment.replace(/^\/+/, "");
    const joined = `${base}/${next}`.replace(/\/+/g, "/");
    return joined.startsWith("/") ? joined : `/${joined}`;
  }
}
