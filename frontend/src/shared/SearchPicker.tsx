import type { ComponentChildren, JSX } from "preact";
import { useEffect, useId, useMemo, useRef, useState } from "preact/hooks";

/** A selectable value with searchable text and optional custom display content. */
export interface SearchPickerOption {
  value: string;
  label: string;
  searchText?: string;
  content?: ComponentChildren;
}

/** Render a controlled searchable menu, optionally accepting a typed value outside its options. */
export function SearchPicker({
  options, selected, query, open, disabled, label, placeholder, emptyMessage,
  onFocus, onClose, onQuery, onSelect, allowCustomValue = false,
}: {
  options: SearchPickerOption[];
  selected: string;
  query: string;
  open: boolean;
  disabled: boolean;
  label: string;
  placeholder: string;
  emptyMessage: string;
  allowCustomValue?: boolean;
  onFocus: () => void;
  onClose: () => void;
  onQuery: (value: string) => void;
  onSelect: (value: string) => void;
}) {
  const id = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const blurTimerRef = useRef<number | null>(null);
  const [activeValue, setActiveValue] = useState("");
  const matchingOptions = useMemo(() => {
    const search = query.trim().toLowerCase();
    if (!search || (!allowCustomValue && query === selected)) return options;
    return options.filter((option) => (option.searchText ?? option.label).toLowerCase().includes(search));
  }, [options, query, selected, allowCustomValue]);
  const activeIndex = matchingOptions.findIndex((option) => option.value === activeValue);
  const optionId = (index: number) => `${id}-option-${index}`;

  useEffect(() => {
    if (open && !disabled && activeIndex >= 0) {
      document.getElementById(optionId(activeIndex))?.scrollIntoView({ block: "nearest" });
    }
  }, [open, disabled, activeIndex]);

  useEffect(() => () => {
    if (blurTimerRef.current !== null) window.clearTimeout(blurTimerRef.current);
  }, []);

  const openMenu = () => {
    if (blurTimerRef.current !== null) window.clearTimeout(blurTimerRef.current);
    setActiveValue(selected);
    onFocus();
  };
  const closeMenu = () => { setActiveValue(""); onClose(); };
  const handleKeyDown = (event: JSX.TargetedKeyboardEvent<HTMLInputElement>) => {
    // Chinese input methods use Enter and arrow keys to confirm composed text.
    if (event.isComposing) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      onFocus();
      const nextIndex = event.key === "ArrowDown"
        ? (open ? Math.min(activeIndex + 1, matchingOptions.length - 1) : 0)
        : (open && activeIndex >= 0 ? Math.max(activeIndex - 1, 0) : matchingOptions.length - 1);
      setActiveValue(matchingOptions[nextIndex]?.value ?? "");
    } else if (event.key === "Enter" && open) {
      // Preserve manually typed names unless the user navigated to a suggestion.
      event.preventDefault();
      const option = matchingOptions[activeIndex >= 0 ? activeIndex : 0];
      if (allowCustomValue && activeIndex < 0) onSelect(query.trim());
      else if (option) onSelect(option.value);
    } else if (event.key === "Escape" && open) {
      event.preventDefault();
      closeMenu();
    } else if (event.key === "Tab") {
      closeMenu();
    }
  };

  return (
    <div className="field package-target" ref={rootRef}>
      <label htmlFor={`${id}-search`}>{label}</label>
      <div className={`package-combobox ${open && !disabled ? "package-combobox--open" : ""}`}>
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
            aria-activedescendant={open && !disabled && activeIndex >= 0 ? optionId(activeIndex) : undefined}
            value={query}
            disabled={disabled}
            onFocus={openMenu}
            onClick={() => { if (!open) openMenu(); }}
            onBlur={() => {
              blurTimerRef.current = window.setTimeout(() => {
                if (!rootRef.current?.contains(document.activeElement)) closeMenu();
              }, 120);
            }}
            onInput={(event) => { setActiveValue(""); onQuery(event.currentTarget.value); }}
            onKeyDown={handleKeyDown}
            placeholder={placeholder}
            autoComplete="off"
            spellcheck={false}
          />
        </div>
        <div id={`${id}-options`} className="package-options" role="listbox" aria-label={label} hidden={!open || disabled}>
          {matchingOptions.length === 0 ? <div className="package-options-empty">{emptyMessage}</div> : matchingOptions.map((option, index) => (
            <div
              id={optionId(index)}
              className={`package-option ${index === activeIndex ? "package-option--active" : ""}`}
              role="option"
              aria-selected={option.value === selected}
              key={option.value}
              onMouseDown={(event) => event.preventDefault()}
              onMouseEnter={() => setActiveValue(option.value)}
              onClick={() => onSelect(option.value)}
            >
              {option.content ?? <span className="package-option-name">{option.label}</span>}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
