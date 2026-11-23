/*
Story ID: 124442
Title: UI: Sculpt Issues and Bug Fixes
Language: TypeScript — the repo is a Vite React TypeScript UI codebase, and the story is explicitly a UI fix for Sculpt in that frontend.
What this code does: Adds a hardened Custom Component properties/editor implementation that fixes common Sculpt issues around code editing persistence, modal lifecycle, preview refresh, and event-handler preservation while staying aligned with the repo’s React + TypeScript patterns.
Assumptions:
- # ASSUMPTION: The work item is broad and does not list exact bug repro steps, comments, or acceptance criteria beyond fixing known Sculpt UI issues, so this file targets a visible high-risk Sculpt area found in repo content: the Custom Component editor/properties flow.
- # ASSUMPTION: This single-file deliverable is intended for repo integration and combines component, modal, and helper logic in one file per the single-file requirement.
- Missing from work item response: acceptance criteria details, comments/discussion, attachments, related links, repro steps, and custom fields beyond basic metadata.
*/

import React, { useEffect, useMemo, useRef, useState } from "react";
import Editor from "@monaco-editor/react";
import {
  Braces,
  ChevronDown,
  ChevronUp,
  Code,
  Code2,
  Expand,
  Eye,
  Palette,
  RefreshCcw,
} from "lucide-react";
import EventHandlersPanel from "../src/components/ui/EventHandlers/EventHandlersPanel";
import { widgetActionHandlers } from "../src/types/ui/WidgetActionHandlers";

const langIcons: Record<string, JSX.Element> = {
  html: <Code className="w-4 h-4 mr-2" />,
  css: <Palette className="w-4 h-4 mr-2" />,
  js: <Braces className="w-4 h-4 mr-2" />,
};

type EditorTab = "html" | "css" | "js";

interface SculptCustomComponentRuntimeProps {
  html: string;
  css: string;
  js: string;
  onSelect?: () => void;
}

interface SculptCustomCodeEditorProps {
  isOpen: boolean;
  onClose: () => void;
  initialTab: EditorTab;
  html: string;
  css: string;
  js: string;
  onSave: (data: { html: string; css: string; js: string }) => void;
}

export interface SculptCustomComponentProps {
  html: string;
  css: string;
  js: string;
}

interface SculptCustomComponentPropertiesPanelProps {
  value: SculptCustomComponentProps & {
    widgetActionHandlers?: widgetActionHandlers[];
  };
  onChange: (
    props: SculptCustomComponentProps & {
      widgetActionHandlers?: widgetActionHandlers[];
    },
  ) => void;
}

function escapeTemplateLiteral(value: string): string {
  return value.replace(/\\/g, "\\\\").replace(/`/g, "\\`");
}

function normalizeCode(value: string | undefined | null): string {
  return value ?? "";
}

function useLatestRef<T>(value: T): React.MutableRefObject<T> {
  const ref = useRef<T>(value);
  useEffect(() => {
    ref.current = value;
  }, [value]);
  return ref;
}

function SculptModal({
  open,
  onClose,
  width = "94%",
  height = "92%",
  children,
}: {
  open: boolean;
  onClose: () => void;
  width?: string;
  height?: string;
  children: React.ReactNode;
}): React.ReactElement | null {
  useEffect(() => {
    if (!open) return undefined;

    const handleKeyDown = (event: KeyboardEvent): void => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/50 p-3">
      <div
        className="relative overflow-hidden rounded-xl bg-white shadow-xl"
        style={{ width, height }}
        onClick={(event) => event.stopPropagation()}
      >
        <button
          type="button"
          aria-label="Close"
          className="absolute right-3 top-3 z-10 rounded-full bg-gray-200 p-2 hover:bg-gray-300"
          onClick={onClose}
        >
          ✕
        </button>
        <div className="h-full w-full">{children}</div>
      </div>
      <button
        type="button"
        aria-label="Dismiss modal overlay"
        className="absolute inset-0 -z-10 cursor-default"
        onClick={onClose}
      />
    </div>
  );
}

function SculptCustomComponent({
  html,
  css,
  js,
  onSelect,
}: SculptCustomComponentRuntimeProps): React.ReactElement {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const onSelectRef = useLatestRef(onSelect);

  useEffect(() => {
    const iframe = iframeRef.current;
    const doc = iframe?.contentDocument;
    if (!iframe || !doc) return;

    const safeHtml = normalizeCode(html);
    const safeCss = normalizeCode(css);
    const safeJs = escapeTemplateLiteral(normalizeCode(js));

    doc.open();
    doc.write(`
      <!DOCTYPE html>
      <html>
        <head>
          <meta charset="UTF-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0" />
          <style>
            html, body {
              margin: 0;
              padding: 0;
              width: 100%;
              min-height: 100%;
              box-sizing: border-box;
              font-family: Inter, system-ui, sans-serif;
            }
            *, *::before, *::after { box-sizing: inherit; }
            ${safeCss}
          </style>
          <script type="module">
            import initSwc, { transform } from "https://cdn.jsdelivr.net/npm/@swc/wasm-web@1.4.1/wasm-web.js";

            window.SWC_READY = initSwc();
            window.SWC_TRANSFORM = transform;
            window.addEventListener("click", () => {
              parent.postMessage({ type: "IFRAME_CLICK" }, "*");
            });
          </script>
        </head>
        <body>
          <div id="root">${safeHtml}</div>
          <script type="module">
            (async () => {
              await window.SWC_READY;
              const userJs = \`${safeJs}\`;

              if (!userJs.trim()) {
                return;
              }

              try {
                const output = await window.SWC_TRANSFORM(userJs, {
                  jsc: {
                    parser: { syntax: "ecmascript", jsx: true },
                    transform: {
                      react: {
                        runtime: "automatic",
                        importSource: "https://esm.sh/react@18"
                      }
                    }
                  },
                  module: { type: "es6" }
                });

                const blob = new Blob([output.code], { type: "text/javascript" });
                const url = URL.createObjectURL(blob);
                try {
                  await import(url);
                } finally {
                  URL.revokeObjectURL(url);
                }
              } catch (err) {
                document.body.insertAdjacentHTML(
                  "beforeend",
                  "<pre style='color:red;white-space:pre-wrap;padding:12px;margin:0'>" + String(err) + "</pre>",
                );
              }
            })();
          </script>
        </body>
      </html>
    `);
    doc.close();
  }, [html, css, js]);

  useEffect(() => {
    const handleMessage = (event: MessageEvent): void => {
      if (event.data?.type === "IFRAME_CLICK") {
        onSelectRef.current?.();
      }
    };

    window.addEventListener("message", handleMessage);
    return () => window.removeEventListener("message", handleMessage);
  }, [onSelectRef]);

  return (
    <iframe
      ref={iframeRef}
      style={{ width: "100%", height: "100%", border: "none" }}
      sandbox="allow-scripts allow-same-origin allow-modals"
      title="Sculpt Custom Component Preview"
    />
  );
}

function SculptCustomCodeEditor({
  isOpen,
  onClose,
  initialTab,
  html,
  css,
  js,
  onSave,
}: SculptCustomCodeEditorProps): React.ReactElement | null {
  const [activeTab, setActiveTab] = useState<EditorTab>(initialTab);
  const [localHtml, setLocalHtml] = useState<string>(normalizeCode(html));
  const [localCss, setLocalCss] = useState<string>(normalizeCode(css));
  const [localJs, setLocalJs] = useState<string>(normalizeCode(js));
  const [previewKey, setPreviewKey] = useState<number>(0);

  useEffect(() => {
    if (!isOpen) return;
    setActiveTab(initialTab);
    setLocalHtml(normalizeCode(html));
    setLocalCss(normalizeCode(css));
    setLocalJs(normalizeCode(js));
  }, [initialTab, isOpen, html, css, js]);

  const editorValue = useMemo(() => {
    if (activeTab === "html") return localHtml;
    if (activeTab === "css") return localCss;
    return localJs;
  }, [activeTab, localHtml, localCss, localJs]);

  const setEditorValue = (nextValue: string): void => {
    if (activeTab === "html") setLocalHtml(nextValue);
    else if (activeTab === "css") setLocalCss(nextValue);
    else setLocalJs(nextValue);
  };

  const tabs: ReadonlyArray<{ id: EditorTab; label: string; color: string }> = [
    { id: "html", label: "HTML", color: "text-orange-400" },
    { id: "css", label: "CSS", color: "text-blue-400" },
    { id: "js", label: "JavaScript", color: "text-yellow-300" },
  ];

  return (
    <SculptModal open={isOpen} onClose={onClose}>
      <div className="flex h-full flex-col overflow-hidden rounded-xl bg-[#0e1116] text-white">
        <div className="flex items-center justify-between border-b border-gray-700 bg-[#11141b] px-6 py-4">
          <div className="flex items-center gap-3 text-lg font-semibold">
            <Code2 className="text-blue-400" size={22} />
            Custom Component Editor
          </div>
        </div>

        <div className="flex flex-1 overflow-hidden">
          <div className="flex flex-[0.58] flex-col border-r border-gray-800 bg-[#0d0f14]">
            <div className="flex gap-1 border-b border-gray-800 bg-[#11141b] px-3">
              {tabs.map((tab) => (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => setActiveTab(tab.id)}
                  className={`rounded-t-md px-4 py-2 text-sm font-medium transition-all ${
                    activeTab === tab.id
                      ? "border-b-2 border-blue-500 bg-[#1b1f27] shadow-inner"
                      : "text-gray-400 hover:text-white"
                  }`}
                >
                  <span className={tab.color}>{tab.label}</span>
                </button>
              ))}
            </div>

            <div className="flex-1 overflow-hidden">
              <Editor
                language={
                  activeTab === "html"
                    ? "html"
                    : activeTab === "css"
                      ? "css"
                      : "javascript"
                }
                theme="vs-dark"
                value={editorValue}
                onChange={(value) => setEditorValue(value ?? "")}
                options={{
                  fontSize: 14,
                  minimap: { enabled: false },
                  smoothScrolling: true,
                  wordWrap: "on",
                  automaticLayout: true,
                }}
              />
            </div>
          </div>

          <div className="flex flex-[0.42] flex-col bg-[#11141b] p-4">
            <div className="mb-3 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Eye size={18} className="text-green-400" />
                <span className="text-sm text-gray-300">Live Preview</span>
              </div>
              <button
                type="button"
                onClick={() => setPreviewKey((current) => current + 1)}
                className="text-gray-400 hover:text-white"
                aria-label="Refresh preview"
              >
                <RefreshCcw size={18} />
              </button>
            </div>

            <div className="flex-1 overflow-auto rounded-lg border border-gray-700 bg-white p-4 shadow-lg">
              <SculptCustomComponent
                key={previewKey}
                html={localHtml}
                css={localCss}
                js={localJs}
              />
            </div>
          </div>
        </div>

        <div className="flex justify-end gap-3 border-t border-gray-800 bg-[#11141b] px-6 py-4">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-600 px-4 py-2 text-gray-300 hover:bg-gray-800"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => {
              onSave({ html: localHtml, css: localCss, js: localJs });
              onClose();
            }}
            className="rounded-md bg-blue-600 px-5 py-2 text-white shadow hover:bg-blue-700"
          >
            Save All Changes
          </button>
        </div>
      </div>
    </SculptModal>
  );
}

const SculptIssueFixes = ({
  value,
  onChange,
}: SculptCustomComponentPropertiesPanelProps): React.ReactElement => {
  const [editorState, setEditorState] = useState<{
    open: boolean;
    activeTab: EditorTab;
  }>({
    open: false,
    activeTab: "html",
  });
  const [dropdownOpen, setDropdownOpen] = useState<boolean>(false);

  const safeValue = useMemo(
    () => ({
      html: normalizeCode(value.html),
      css: normalizeCode(value.css),
      js: normalizeCode(value.js),
      widgetActionHandlers: value.widgetActionHandlers ?? [],
    }),
    [value.css, value.html, value.js, value.widgetActionHandlers],
  );

  const openEditor = (tab: EditorTab): void => {
    setEditorState({ open: true, activeTab: tab });
  };

  return (
    <div className="mt-2 space-y-3">
      <button
        type="button"
        onClick={() => setDropdownOpen((current) => !current)}
        className="w-full rounded-md border bg-gradient-to-r px-4 py-2 text-sm font-medium shadow-sm transition-all duration-200"
      >
        <div className="flex items-center justify-between">
          <span>Custom Component Code</span>
          {dropdownOpen ? (
            <ChevronUp className="h-5 w-5" />
          ) : (
            <ChevronDown className="h-5 w-5" />
          )}
        </div>
      </button>

      <div
        className={`flex flex-col gap-2 overflow-hidden transition-all duration-300 ${
          dropdownOpen ? "mt-2 max-h-60 opacity-100" : "max-h-0 opacity-0"
        }`}
      >
        {(["html", "css", "js"] as EditorTab[]).map((lang) => (
          <button
            key={lang}
            type="button"
            onClick={() => openEditor(lang)}
            className="flex w-full items-center justify-between rounded-md border border-gray-600 bg-gradient-to-r from-[#798bcd] to-[#4b4f5c] px-4 py-2 text-sm font-semibold text-gray-100 shadow-md transition-all duration-200 hover:from-[#4b4f5c] hover:to-[#7f8bac]"
          >
            <div className="flex items-center">
              {langIcons[lang]}
              <span>{lang.toUpperCase()}</span>
            </div>
            <Expand className="h-4 w-4" />
          </button>
        ))}
      </div>

      <SculptCustomCodeEditor
        isOpen={editorState.open}
        initialTab={editorState.activeTab}
        html={safeValue.html}
        css={safeValue.css}
        js={safeValue.js}
        onClose={() => setEditorState((current) => ({ ...current, open: false }))}
        onSave={(newData) => {
          onChange({
            ...safeValue,
            html: newData.html,
            css: newData.css,
            js: newData.js,
          });
        }}
      />

      <EventHandlersPanel
        widgetType="customComponent"
        widgetName="customComponent"
        value={safeValue.widgetActionHandlers}
        onChange={(next) =>
          onChange({
            ...safeValue,
            widgetActionHandlers: next,
          })
        }
        eventTypes={[{ value: "onClick", label: "onClick" }]}
        helperText="Optional: configure custom component event handlers (runtime support may vary)."
      />
    </div>
  );
};

export default SculptIssueFixes;
