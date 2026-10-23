import type { JSX } from "preact";
import { useEffect, useId, useMemo, useRef, useState } from "preact/hooks";
import type { SyscallCatalogEntry } from "./model";

/** Short tag shown next to each catalog row. */
function availabilityLabel(entry: SyscallCatalogEntry): string | null {
  if (entry.availability === "arch_unsupported") return "not implemented";
  if (entry.availability === "kernel_disabled") return "not enabled";
  return null;
}

function isSelectable(entry: SyscallCatalogEntry): boolean {
  return entry.availability === "available";
}

/** "NR · name" — the same shape the events pane uses for a chosen syscall. */
function labelOf(entry: SyscallCatalogEntry): string {
  return `${entry.nr} · ${entry.name}`;
}

/** Search text covers everything a user might type, including tag wording. */
function searchTextOf(entry: SyscallCatalogEntry): string {
  const tag = availabilityLabel(entry) ?? "";
  const schema = entry.schema ? "args schema auto" : "";
  return `${entry.nr} ${entry.name} ${tag} ${schema}`.toLowerCase();
}

/**
 * Searchable arm64 syscall menu.
 *
 * Unimplemented numbers stay listed so their absence is visible, but they are
 * not selectable: a rule on one can never produce an event.
 */
export function SyscallPicker({
  entries, selected, disabled, onSelect,
}: {
  entries: SyscallCatalogEntry[];
  selected: number | null;
  disabled: boolean;
  onSelect: (entry: SyscallCatalogEntry) => void;
}) {
  const id = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const blurTimerRef = useRef<number | null>(null);
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [activeValue, setActiveValue] = useState<number | null>(null);

  const selectedEntry = useMemo(
    () => entries.find((entry) => entry.nr === selected) ?? null,
    [entries, selected],
  );
  const selectedLabel = selectedEntry ? labelOf(selectedEntry) : "";

  // The field shows the selection when it is not being searched, so the form
  // still summarises what it will send once the menu closes.
  const displayValue = selectedEntry && (query === "" || query === selectedLabel) ? selectedLabel : query;

  const matches = useMemo(() => {
    const search = query.trim().toLowerCase();
    if (!search || query === selectedLabel) return entries;
    return entries.filter((entry) => searchTextOf(entry).includes(search));
  }, [entries, query, selectedLabel]);

  // Unavailable rows are shown but skipped by keyboard navigation.
  const selectable = useMemo(() => matches.filter(isSelectable), [matches]);
  const activeIndex = activeValue == null ? -1 : selectable.findIndex((entry) => entry.nr === activeValue);
  const optionId = (nr: number) => `${id}-option-${nr}`;

  useEffect(() => {
    if (open && !disabled && activeValue != null) {
      document.getElementById(optionId(activeValue))?.scrollIntoView({ block: "nearest" });
    }
  }, [open, disabled, activeValue]);

  useEffect(() => () => {
    if (blurTimerRef.current !== null) window.clearTimeout(blurTimerRef.current);
  }, []);

  const openMenu = () => {
    if (blurTimerRef.current !== null) window.clearTimeout(blurTimerRef.current);
    setActiveValue(selected);
    setOpen(true);
  };
  const closeMenu = () => { setOpen(false); setActiveValue(null); };

  const pick = (entry: SyscallCatalogEntry) => {
    if (!isSelectable(entry)) return;
    onSelect(entry);
    setQuery(labelOf(entry));
    closeMenu();
  };

  const handleKeyDown = (event: JSX.TargetedKeyboardEvent<HTMLInputElement>) => {
    // Chinese input methods use Enter and arrow keys to confirm composed text.
    if (event.isComposing) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      openMenu();
      const current = activeIndex;
      const nextIndex = event.key === "ArrowDown"
        ? (current < 0 ? 0 : Math.min(current + 1, selectable.length - 1))
        : (current < 0 ? selectable.length - 1 : Math.max(current - 1, 0));
      setActiveValue(selectable[nextIndex]?.nr ?? null);
    } else if (event.key === "Enter") {
      if (!open) return;
      event.preventDefault();
      const entry = selectable[activeIndex < 0 ? 0 : activeIndex];
      if (entry) pick(entry);
    } else if (event.key === "Escape" && open) {
      event.preventDefault();
      closeMenu();
    } else if (event.key === "Tab") {
      closeMenu();
    }
  };

  return (
    <div className="field syscall-target" ref={rootRef}>
      <label htmlFor={`${id}-search`}>Syscall</label>
      <div className={`package-combobox syscall-combobox ${open && !disabled ? "package-combobox--open" : ""}`}>
        <div className="package-combobox-control">
          <input
            id={`${id}-search`}
            className="package-search"
            type="search"
            role="combobox"
            aria-autocomplete="list"
            aria-haspopup="listbox"
            aria-expanded={open && !disabled}
            aria-controls={`${id}-options`}
            aria-activedescendant={open && !disabled && activeValue != null ? optionId(activeValue) : undefined}
            value={displayValue}
            disabled={disabled}
            onFocus={openMenu}
            onClick={() => { if (!open) openMenu(); }}
            onBlur={() => {
              blurTimerRef.current = window.setTimeout(() => {
                if (!rootRef.current?.contains(document.activeElement)) closeMenu();
              }, 120);
            }}
            onInput={(event) => { setActiveValue(null); setQuery(event.currentTarget.value); setOpen(true); }}
            onKeyDown={handleKeyDown}
            placeholder="搜尋編號或名稱（如 63、read、openat）"
            autoComplete="off"
            spellcheck={false}
          />
        </div>
        <div id={`${id}-options`} className="package-options syscall-options" role="listbox" aria-label="Syscall" hidden={!open || disabled}>
          {matches.length === 0 && <div className="package-options-empty">找不到符合的 syscall</div>}
          {matches.map((entry) => {
            const tag = availabilityLabel(entry);
            const selectableRow = isSelectable(entry);
            return (
              <div
                id={optionId(entry.nr)}
                className={`package-option syscall-option ${entry.nr === activeValue ? "package-option--active" : ""} ${selectableRow ? "" : "syscall-option--unavailable"}`}
                role="option"
                aria-selected={entry.nr === selected}
                aria-disabled={!selectableRow}
                key={entry.nr}
                onMouseDown={(event) => event.preventDefault()}
                onMouseEnter={() => { if (selectableRow) setActiveValue(entry.nr); }}
                onClick={() => pick(entry)}
              >
                <div className="syscall-option-primary">
                  <span className="syscall-nr">{entry.nr}</span>
                  <span className="syscall-name">{entry.name}</span>
                  {entry.schema && <span className="syscall-tag syscall-tag--schema">args</span>}
                  {entry.enterOnly && <span className="syscall-tag syscall-tag--enter">enter only</span>}
                  {tag && <span className="syscall-tag syscall-tag--unavailable">{tag}</span>}
                </div>
              </div>
            );
          })}
        </div>
      </div>
      <p className="syscall-hint">
        <span className="syscall-hint-count">{matches.length} 項</span>
        {selectedEntry && <span className="syscall-hint-selected">{selectedEntry.schema ? `${selectedEntry.name} · 自動參數解析` : selectedEntry.name}</span>}
      </p>
    </div>
  );
}
